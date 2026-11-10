import { h } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { OperationImpact } from '@/api/lsmom/types';

export interface ConfirmTarget {
  /** 动作名，如"验证通过" */
  name: string;
  /** 对象说明，如"问题 IS-0012 传送带跑偏" */
  label?: string;
  /** 取影响说明；取不到时只说明不能撤销 */
  impact?: () => Promise<OperationImpact | null | undefined>;
  stateLabel?: (code?: string | null) => string;
}

/**
 * 不可撤销动作的确认框：列出影响对象（服务端影响说明）、状态变化、进入终态后不能再修改，并说明不能撤销。
 * 返回 true 表示确认执行。
 */
export async function confirmOperation(t: ConfirmTarget): Promise<boolean> {
  let imp: OperationImpact | null | undefined = null;
  try {
    imp = await t.impact?.();
  } catch {
    imp = null;
  }
  const label = (c?: string | null) => (c ? (t.stateLabel ? t.stateLabel(c) : c) : '');
  const lines: string[] = [];
  if (imp?.lines?.length) lines.push(...imp.lines);
  else if (t.label) lines.push(`对象：${t.label}`);
  if (imp?.fromStatus && imp.toStatus && imp.fromStatus !== imp.toStatus) lines.push(`状态：${label(imp.fromStatus)} → ${label(imp.toStatus)}`);
  if (imp?.terminal) lines.push(`${t.name}后流程结束，不能再编辑或办理`);
  lines.push('此操作不能撤销');
  try {
    await ElMessageBox.confirm(
      h(
        'ul',
        { class: 'ls-op-confirm', 'data-testid': 'op-confirm-body' },
        lines.map((l) => h('li', l))
      ),
      `确定${t.name}？`,
      { type: 'warning', confirmButtonText: t.name, cancelButtonText: '取消', customClass: 'ls-op-confirm-box' }
    );
    return true;
  } catch {
    return false;
  }
}
