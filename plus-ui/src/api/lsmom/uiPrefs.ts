import request from '@/utils/request';
import type { NavEntry } from '@/store/modules/navHistory';

export interface UiPrefs {
  theme?: 'system' | 'light' | 'dark';
  density?: 'auto' | 'comfortable' | 'compact';
  navType?: 'left' | 'top' | 'mix';
  tagsView?: boolean;
  hotkeyHints?: boolean;
}

export const getPrefs = (): Promise<{ prefs: UiPrefs; recent: NavEntry[]; favorites: NavEntry[] }> =>
  request({ url: '/api/ui/prefs', method: 'get' }).then((r: any) => r.data);
export const putPrefs = (p: UiPrefs) => request({ url: '/api/ui/prefs', method: 'put', data: p });
export const putNav = (nav: { recent: NavEntry[]; favorites: NavEntry[] }) => request({ url: '/api/ui/prefs/nav', method: 'put', data: nav });
