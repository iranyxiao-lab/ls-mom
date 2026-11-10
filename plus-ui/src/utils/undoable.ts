import { h } from 'vue';
import { ElMessage, type MessageHandler } from 'element-plus';

/** 执行结果里的撤销方式（服务端登记了补偿 Operation 时才有） */
export interface UndoableResult {
  version: number;
  warnings?: string[] | null;
  undo?: { operation: string; targetId: string; input: Record<string, unknown> } | null;
}

export interface UndoableRun<T extends UndoableResult> {
  /** 完成提示，如"已移到中班" */
  done: string;
  /** 乐观更新：先改界面 */
  apply?: () => void;
  /** 恢复界面（执行失败或撤销时） */
  revert?: () => void;
  /** 执行动作 */
  exec: () => Promise<T>;
  /** 执行补偿 Operation：对同一对象、以执行结果的版本调用 */
  compensate: (operation: string, targetId: string, version: number, input: Record<string, unknown>) => Promise<unknown>;
  /** 撤销完成（或撤销失败）后：通常是重新加载 */
  after?: () => unknown;
}

/**
 * 低风险、可逆的动作：先乐观更新界面，再执行；成功后提示里给出"撤销"，默认 5 秒内有效。
 * 撤销调用服务端为该动作登记的补偿 Operation（`meta_operation.undo_operation`），由服务端恢复数据。
 * 没有补偿 Operation 的动作不走这里，改走确认框（见 `confirmOperation`）。
 */
export function useUndoable(seconds = 5) {
  let current: MessageHandler | null = null;

  /** 显示完成提示；onUndo 不为空时附"撤销"按钮（只能点一次，到时自动消失） */
  function offer(text: string, onUndo?: () => unknown, type: 'success' | 'warning' = 'success') {
    current?.close();
    let used = false;
    const click = async () => {
      if (used || !onUndo) return;
      used = true;
      current?.close();
      await onUndo();
    };
    current = ElMessage({
      type,
      duration: (onUndo ? seconds : type === 'warning' ? 6 : 3) * 1000,
      customClass: 'ls-undo-msg',
      message: h('span', { class: 'ls-undo' }, [
        h('span', { 'data-testid': 'undo-text' }, text),
        onUndo ? h('button', { type: 'button', class: 'ls-undo__btn', 'data-testid': 'undo-btn', onClick: click }, '撤销') : null
      ])
    });
  }

  async function run<T extends UndoableResult>(o: UndoableRun<T>): Promise<T | undefined> {
    o.apply?.();
    let r: T;
    try {
      r = await o.exec();
    } catch (e) {
      o.revert?.();
      throw e;
    }
    const warn = r.warnings?.length ? `。注意：${r.warnings.join('；')}` : '';
    const undo = r.undo;
    offer(
      o.done + warn,
      undo
        ? async () => {
            o.revert?.();
            try {
              await o.compensate(undo.operation, undo.targetId, r.version, undo.input);
              ElMessage.success('已撤销');
            } finally {
              await o.after?.();
            }
          }
        : undefined,
      warn ? 'warning' : 'success'
    );
    return r;
  }

  return { run, offer };
}
