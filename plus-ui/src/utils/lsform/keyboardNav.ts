/**
 * 表单键盘录入：按页面顺序找下一个可录入的字段（跳过只读、附件类与多行文本以外的按钮），子表单元格同样参与。
 * 字段容器带 data-field-key（字段键或 子表[行].列）。
 */
const INPUT = 'input:not([type="hidden"]):not([type="file"]):not([disabled]):not([readonly]), textarea:not([disabled]):not([readonly])';

export function inputOf(host: Element): HTMLElement | null {
  return host.querySelector<HTMLElement>(INPUT);
}

/** 所有可录入的字段容器（按 DOM 顺序）。 */
export function fieldHosts(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-field-key]')].filter((h) => h.getClientRects().length > 0 && inputOf(h));
}

/** 下拉选择当前是否展开（展开时 Enter 交给下拉框选中选项）。 */
export function selectOpen(target: HTMLElement): boolean {
  const combo = target.closest('.el-select, .el-cascader, .el-date-editor');
  if (!combo) return false;
  const expanded = combo.querySelector('[aria-expanded="true"]') ?? (target.getAttribute('aria-expanded') === 'true' ? target : null);
  return !!expanded;
}

export function focusHost(host: Element | null | undefined): boolean {
  const input = host ? inputOf(host) : null;
  if (!input) return false;
  input.focus();
  if (input instanceof HTMLInputElement && ['text', 'search', 'number', 'tel', ''].includes(input.type)) input.select?.();
  return true;
}

const CELL = /^([A-Za-z][A-Za-z0-9]*)\[(\d+)]\.([A-Za-z][A-Za-z0-9]*)$/;

export function parseCell(key: string) {
  const m = CELL.exec(key);
  return m ? { table: m[1], row: Number(m[2]), field: m[3] } : null;
}
