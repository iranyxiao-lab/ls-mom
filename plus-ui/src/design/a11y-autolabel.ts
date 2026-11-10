/**
 * 无障碍兜底：给没有可读名称的表单控件补 aria-label。
 * 业务页面的筛选框常只有占位文字（Element Plus 下拉框的占位还是独立文本节点，读屏读不到），这里按以下顺序取名：
 * 所在 el-form-item 的标签 → 控件占位文字 → 外层 data-label。已有名称（aria-label、aria-labelledby、<label for>、包裹的 label）的不改动。
 * 新代码仍应显式写 aria-label 或放在带标签的表单项里；这里只防止遗漏。
 */
const CONTROL = 'input:not([type="hidden"]):not([type="file"]), textarea, [role="combobox"]';

function hasName(el: HTMLElement): boolean {
  if (el.getAttribute('aria-label')?.trim() || el.getAttribute('aria-labelledby')) return true;
  if (el.id && document.querySelector(`label[for="${CSS.escape(el.id)}"]`)?.textContent?.trim()) return true;
  if (el.closest('label')?.textContent?.trim()) return true;
  if (el.getAttribute('title')?.trim()) return true;
  return false;
}

function guessName(el: HTMLElement): string | null {
  const item = el.closest('.el-form-item');
  const label = item?.querySelector(':scope > .el-form-item__label, :scope > .el-form-item__label-wrap .el-form-item__label')?.textContent?.trim();
  if (label) return label.replace(/[:：]$/, '');
  const ph = (el as HTMLInputElement).placeholder?.trim();
  if (ph) return ph;
  const wrap = el.closest('.el-select, .el-select-v2, .el-cascader, .el-date-editor, .el-tree-select');
  const shown = wrap?.querySelector('.el-select__placeholder, .el-select-v2__placeholder')?.textContent?.trim();
  if (shown) return shown;
  const host = el.closest('[data-label]')?.getAttribute('data-label');
  return host || null;
}

function label(root: ParentNode) {
  root.querySelectorAll<HTMLElement>(CONTROL).forEach((el) => {
    if (el.dataset.lsAutolabel || hasName(el)) return;
    const name = guessName(el);
    if (name) {
      el.setAttribute('aria-label', name);
      el.dataset.lsAutolabel = '1';
    }
  });
}

let pending = false;
export function installAutoLabel(target: HTMLElement = document.body) {
  label(target);
  new MutationObserver(() => {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => {
      pending = false;
      label(target);
    });
  }).observe(target, { childList: true, subtree: true });
}
