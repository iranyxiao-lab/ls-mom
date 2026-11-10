import { h, type VNode } from 'vue';
import { ElNotification, type NotificationHandle } from 'element-plus';

/** 服务端给出的修复入口：`to` 跳转页面；`action` 交给当前页面登记的处理函数（如"改派他人"） */
export interface FixAction {
  label: string;
  to?: string;
  action?: string;
  [k: string]: unknown;
}

export interface ActionableError {
  message: string;
  errorCode?: string;
  details?: Record<string, any>;
  trace?: string;
}

type FixHandler = (fix: FixAction, err: ActionableError) => unknown;
const handlers = new Map<string, FixHandler[]>();

/**
 * 页面登记修复动作的处理函数（页面卸载时调用返回值注销）。
 * 没有页面处理的 `action` 不显示按钮，避免点了没反应。
 */
export function registerFixAction(action: string, handler: FixHandler): () => void {
  const list = handlers.get(action) ?? [];
  list.push(handler);
  handlers.set(action, list);
  return () => {
    const l = handlers.get(action) ?? [];
    handlers.set(
      action,
      l.filter((x) => x !== handler)
    );
  };
}

let navigate: (to: string) => unknown = (to) => {
  window.location.href = to;
};
/** 由应用入口注入路由跳转（避免本模块静态依赖路由） */
export function setFixNavigator(fn: (to: string) => unknown) {
  navigate = fn;
}

/** 业务错误编码的通用说明与修复（服务端没有给出时使用） */
const GENERIC: Record<string, { why?: string; fix?: FixAction[] }> = {
  VERSION_CONFLICT: { why: '你打开之后，别人修改了这条记录。', fix: [{ label: '刷新后重试', action: 'reload' }] },
  ACCESS_DENIED: { why: '你的账号没有这项权限，或对象不在你的数据范围内。请联系管理员开通。' },
  FORBIDDEN: { why: '你的账号没有这项权限。请联系管理员开通。' },
  INVALID_TRANSITION: { why: '这条记录的状态已经变化，当前不能执行这个动作。', fix: [{ label: '刷新后重试', action: 'reload' }] }
};

function usable(fix: FixAction) {
  return !!fix.to || (!!fix.action && ((handlers.get(fix.action)?.length ?? 0) > 0 || fix.action === 'reload'));
}

let current: NotificationHandle | null = null;

/**
 * 可操作的错误提示：发生了什么（标题）、为什么、怎么解决（修复按钮），技术细节折叠显示并附追踪编号。
 * 不展示堆栈；没有说明时只显示服务端给出的业务原因。
 */
export function showActionableError(err: ActionableError) {
  const d = err.details ?? {};
  const reason = typeof d.reason === 'string' ? d.reason : err.errorCode;
  const generic = (reason && GENERIC[reason]) || (err.errorCode && GENERIC[err.errorCode]) || {};
  const why: string | undefined = d.why ?? generic.why;
  const fixes = ((Array.isArray(d.fix) ? d.fix : generic.fix) ?? []).filter(
    (f: any): f is FixAction => !!f && typeof f.label === 'string' && usable(f)
  );
  const run = async (f: FixAction) => {
    current?.close();
    if (f.to) return navigate(f.to);
    if (f.action === 'reload' && !(handlers.get('reload')?.length ?? 0)) return window.location.reload();
    for (const fn of handlers.get(f.action!) ?? []) await fn(f, err);
  };
  const tech = [
    err.errorCode && `错误编码：${err.errorCode}${reason && reason !== err.errorCode ? ` / ${reason}` : ''}`,
    d.guard && `检查项：${d.guard}`,
    err.trace && `追踪编号：${err.trace}`
  ].filter(Boolean) as string[];
  const body: VNode[] = [];
  if (why) body.push(h('p', { class: 'ls-err__why', 'data-testid': 'error-why' }, why));
  if (fixes.length)
    body.push(
      h(
        'div',
        { class: 'ls-err__fix', 'data-testid': 'error-fixes' },
        fixes.map((f: FixAction) =>
          h(
            'button',
            { type: 'button', class: 'ls-err__btn', 'data-testid': `error-fix-${f.action ?? 'link'}`, onClick: () => run(f) },
            f.label
          )
        )
      )
    );
  if (tech.length)
    body.push(
      h('details', { class: 'ls-err__tech', 'data-testid': 'error-tech' }, [
        h('summary', '技术详情'),
        ...tech.map((t) => h('div', { class: 'ls-err__tech-line' }, t))
      ])
    );
  current?.close();
  current = ElNotification({
    type: 'error',
    title: err.message,
    message: h('div', { class: 'ls-err', 'data-testid': 'error-notice' }, body),
    // 有修复入口时不自动消失，等用户处理
    duration: fixes.length ? 0 : 6000,
    customClass: 'ls-error-notice'
  });
  return current;
}
