/** 界面模式：auto 按设备宽度与用户类型自动选择；desktop / mobile 为手动选择（存服务端偏好，换设备登录后保持） */
export type UiMode = 'auto' | 'desktop' | 'mobile';

/** 现场岗位（操作工、点检员、维修工）：平板宽度也默认用手机版 */
export const FIELD_ROLES = ['operator', 'inspector', 'maintainer', 'repairman'];

/** 是否应使用手机版：手动选择优先；自动时窄屏（< 768）或现场岗位在平板宽度（< 1024） */
export function preferMobile(mode: UiMode, width: number, roles: string[]): boolean {
  if (mode === 'mobile') return true;
  if (mode === 'desktop') return false;
  if (width < 768) return true;
  return width < 1024 && roles.some((r) => FIELD_ROLES.includes(r));
}
