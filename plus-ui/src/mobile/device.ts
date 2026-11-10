import { ref } from 'vue';
import { getPrefs, putPrefs } from '@/api/lsmom/uiPrefs';
import { useUserStore } from '@/store/modules/user';

import { type UiMode } from './mode';

export { FIELD_ROLES, preferMobile, type UiMode } from './mode';

export const uiMode = ref<UiMode>('auto');
let loaded = false;

export async function loadUiMode(): Promise<UiMode> {
  if (loaded) return uiMode.value;
  try {
    const p = (await getPrefs()).prefs as any;
    if (p?.uiMode === 'desktop' || p?.uiMode === 'mobile' || p?.uiMode === 'auto') uiMode.value = p.uiMode;
    loaded = true;
  } catch {
    /* 偏好不可用时按自动 */
  }
  return uiMode.value;
}

export async function setUiMode(m: UiMode) {
  uiMode.value = m;
  loaded = true;
  await putPrefs({ uiMode: m } as any).catch(() => undefined);
}

/** 测试用 */
export function resetUiMode() {
  loaded = false;
  uiMode.value = 'auto';
}

const USER_KEY = 'ls-m-user';
/** 手机版记住当前身份（姓名、编号、角色），离线打开时显示 */
export function rememberOfflineUser() {
  const u = useUserStore();
  try {
    localStorage.setItem(USER_KEY, JSON.stringify({ userId: u.userId, name: u.name, nickname: u.nickname, roles: u.roles }));
  } catch {
    /* 本机存储不可用 */
  }
}
export function restoreOfflineUser(): boolean {
  try {
    const c = JSON.parse(localStorage.getItem(USER_KEY) ?? 'null');
    if (!c) return false;
    const u = useUserStore() as any;
    u.userId = c.userId;
    u.name = c.name;
    u.nickname = c.nickname;
    return true;
  } catch {
    return false;
  }
}
