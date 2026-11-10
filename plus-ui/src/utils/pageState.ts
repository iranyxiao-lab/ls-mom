/**
 * 多页签状态保留：
 * - 未保存内容登记：页面用 useUnsavedGuard 登记「是否有未保存内容」，关闭含未保存内容的页签（或关闭浏览器）时提示；
 * - 滚动位置：页签被缓存（keep-alive）时，离开前记下页面与页面内滚动容器的位置，切回来时恢复；
 * - 页面打开的对话框、抽屉：离开页签时隐藏，切回时恢复（否则会盖住其他页签）。
 * 列表筛选、表单内容等组件状态由 keep-alive 原样保留，不需要额外处理。
 */
import { onBeforeUnmount, onActivated, reactive } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { ElMessageBox } from 'element-plus';

const guards = reactive(new Map<string, Set<() => boolean>>());
let unloadBound = false;

const anyDirty = () => [...guards.values()].some((set) => [...set].some((fn) => fn()));

/** 当前路由页面登记未保存判断；页面卸载时自动注销。 */
export function useUnsavedGuard(isDirty: () => boolean): void {
  // 单元测试等没有路由的场景下按当前地址登记
  const path = useRoute()?.path ?? location.pathname;
  if (!guards.has(path)) guards.set(path, new Set());
  guards.get(path)!.add(isDirty);
  onBeforeUnmount(() => {
    const set = guards.get(path);
    set?.delete(isDirty);
    if (set && !set.size) guards.delete(path);
  });
  if (!unloadBound && typeof window !== 'undefined') {
    unloadBound = true;
    window.addEventListener('beforeunload', (e) => {
      if (anyDirty()) {
        e.preventDefault();
        e.returnValue = '';
      }
    });
  }
}

/** 该路由页面是否有未保存内容。 */
export function hasUnsaved(path: string): boolean {
  return [...(guards.get(path) ?? [])].some((fn) => fn());
}

/** 关闭页签前确认：有未保存内容时弹出提示，返回 true 表示可以关闭。 */
export async function confirmClose(views: { path: string; title?: unknown }[]): Promise<boolean> {
  const dirty = views.filter((v) => hasUnsaved(v.path));
  if (!dirty.length) return true;
  const names = dirty.map((v) => `「${String(v.title ?? v.path)}」`).join('、');
  return ElMessageBox.confirm(`${names}有未保存的内容，关闭后这些内容会丢失。`, '关闭页签', {
    confirmButtonText: '仍然关闭',
    cancelButtonText: '继续编辑',
    type: 'warning'
  })
    .then(() => true)
    .catch(() => false);
}

const scrollables = (root: Element) =>
  [root, ...root.querySelectorAll('.el-scrollbar__wrap, [data-keep-scroll]')].filter((e) => e.scrollTop > 0 || e.scrollLeft > 0);

/**
 * 页签缓存时保留滚动位置（由路由页面外层包装组件统一调用，业务页面无需处理）。
 * 页面级滚动在 window 上；页面内滚动容器（表格、看板等）的节点被 keep-alive 原样保留，记下节点即可恢复。
 */
export function useKeepScroll(getRoot: () => Element | null | undefined): void {
  let windowY = 0;
  let inner: [Element, number, number][] = [];
  // 必须在离开路由前记录：节点被移出文档后滚动位置就归零了
  // 页面打开的对话框、抽屉挂在 body 上，不随页签隐藏：离开时先藏起来，切回时原样显示（填写内容保留在组件里）
  let overlays: HTMLElement[] = [];
  let lockedScroll = false;
  onBeforeRouteLeave(() => {
    windowY = window.scrollY;
    const root = getRoot();
    inner = root instanceof Element ? scrollables(root).map((e) => [e, e.scrollTop, e.scrollLeft]) : [];
    overlays = [...document.querySelectorAll<HTMLElement>('body > .el-overlay, body > .el-drawer__wrapper')].filter(
      (e) => getComputedStyle(e).display !== 'none'
    );
    for (const e of overlays) e.style.setProperty('display', 'none', 'important');
    // 对话框打开时锁定了 body 滚动，离开时一并解除，切回时恢复
    lockedScroll = document.body.classList.contains('el-popup-parent--hidden');
    if (overlays.length && lockedScroll) document.body.classList.remove('el-popup-parent--hidden');
  });
  onActivated(() => {
    for (const e of overlays) e.style.removeProperty('display');
    if (overlays.length && lockedScroll) document.body.classList.add('el-popup-parent--hidden');
    overlays = [];
    // 等过渡动画插入节点后再恢复，最多重试 10 帧
    let n = 0;
    const restore = () => {
      for (const [e, top, left] of inner) {
        e.scrollTop = top;
        e.scrollLeft = left;
      }
      window.scrollTo(0, windowY);
      const off = Math.abs(window.scrollY - windowY) > 1 || inner.some(([e, top]) => Math.abs(e.scrollTop - top) > 1);
      if (off && ++n < 20) requestAnimationFrame(restore);
    };
    requestAnimationFrame(restore);
  });
}
