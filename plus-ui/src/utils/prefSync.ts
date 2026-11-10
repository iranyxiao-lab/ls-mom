/**
 * 偏好中心的服务端同步：登录后读取服务端偏好（主题、密度、布局、页签、快捷键提示）与收藏、最近访问并应用，
 * 之后任何入口（偏好中心、布局设置抽屉、命令面板）修改都会写回服务端。本地存储只作缓存，换浏览器登录后偏好保持。
 */
import { ref, watch } from 'vue';
import { useDensity, useThemeMode } from '@/design/theme';
import { useSettingsStore } from '@/store/modules/settings';
import { useNavHistoryStore } from '@/store/modules/navHistory';
import { useAppStore } from '@/store/modules/app';
import { usePermissionStore } from '@/store/modules/permission';
import { getPrefs, putNav, putPrefs, type UiPrefs } from '@/api/lsmom/uiPrefs';
import { NavTypeEnum } from '@/enums/NavTypeEnum';

/** 是否显示快捷键提示（命令面板底栏、按钮提示）。 */
export const hotkeyHints = ref(localStorage.getItem('ls-hotkey-hints') !== 'false');

let started = false;
let applying = false;

function current(): UiPrefs {
  const s = useSettingsStore();
  return {
    theme: useThemeMode().pref.value,
    density: useDensity().pref.value,
    navType: s.navType as UiPrefs['navType'],
    tagsView: s.tagsView,
    hotkeyHints: hotkeyHints.value
  };
}

function apply(p: UiPrefs) {
  applying = true;
  try {
    const s = useSettingsStore();
    if (p.theme) useThemeMode().pref.value = p.theme;
    if (p.density) useDensity().pref.value = p.density;
    if (p.navType) s.navType = p.navType as NavTypeEnum;
    if (typeof p.tagsView === 'boolean') s.tagsView = p.tagsView;
    if (typeof p.hotkeyHints === 'boolean') hotkeyHints.value = p.hotkeyHints;
    // 布局设置的本地缓存同步更新，刷新页面时先按服务端偏好显示
    try {
      const raw = JSON.parse(localStorage.getItem('layout-setting') || '{}');
      localStorage.setItem('layout-setting', JSON.stringify({ ...raw, navType: s.navType, tagsView: s.tagsView }));
    } catch {
      /* 本地存储不可用时忽略 */
    }
  } finally {
    // 等本轮 watch 回调执行完再恢复，避免把刚读到的值写回服务端
    queueMicrotask(() => (applying = false));
  }
}

function debounce(fn: () => void, ms: number) {
  let t: ReturnType<typeof setTimeout> | undefined;
  return () => {
    clearTimeout(t);
    t = setTimeout(fn, ms);
  };
}

/** 登录后调用一次：读取并应用服务端偏好，然后开始同步。失败时沿用本地缓存，不影响使用。 */
export async function startPrefSync(): Promise<void> {
  if (started) return;
  started = true;
  const history = useNavHistoryStore();
  try {
    const server = await getPrefs();
    apply(server.prefs ?? {});
    // 收藏与最近访问以服务端为准（本地缓存不区分用户，不上传，避免把上一个用户的记录带给下一个用户）
    applying = true;
    history.replace({ recent: server.recent ?? [], favorites: server.favorites ?? [] });
    queueMicrotask(() => (applying = false));
  } catch {
    /* 服务端不可用时只用本地缓存 */
  }

  // 导航布局不论从哪里修改（偏好中心、布局设置抽屉、服务端偏好），侧栏随之切换
  const settings = useSettingsStore();
  watch(
    () => settings.navType,
    (v) => {
      useAppStore().toggleSideBarHide(v === NavTypeEnum.TOP);
      if (v !== NavTypeEnum.MIX) usePermissionStore().setSidebarRouters(usePermissionStore().defaultRoutes as any);
    }
  );
  watch(hotkeyHints, (v) => localStorage.setItem('ls-hotkey-hints', String(v)));
  const pushPrefs = debounce(() => void putPrefs(current()).catch(() => undefined), 300);
  watch(current, () => !applying && pushPrefs(), { deep: true });
  const pushNav = debounce(() => void putNav({ recent: history.recent, favorites: history.favorites }).catch(() => undefined), 800);
  watch(
    () => [history.recent, history.favorites],
    () => !applying && pushNav(),
    { deep: true }
  );
}

/** 退出登录：清掉本机缓存的收藏与最近访问，下一个用户登录后重新读取。 */
export function resetPrefSync(): void {
  started = false;
  useNavHistoryStore().replace({ recent: [], favorites: [] });
}
