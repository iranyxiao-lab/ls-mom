/**
 * 会话过期时原地重新登录：请求收到 401 后弹出登录框（由布局里的 ReloginDialog 提供），登录成功后自动重发原请求，
 * 页面与填写内容都不变。同时过期的多个请求共用一次登录。没有登录框（如登录页）时返回 false，由调用方按原方式处理。
 */
type Opener = () => Promise<boolean>;

let opener: Opener | null = null;
let pending: Promise<boolean> | null = null;

export function registerRelogin(open: Opener | null) {
  opener = open;
}

export function reloginAvailable() {
  return !!opener;
}

export function requestRelogin(): Promise<boolean> {
  if (!opener) return Promise.resolve(false);
  if (!pending) {
    pending = opener()
      .catch(() => false)
      .finally(() => {
        pending = null;
      });
  }
  return pending;
}
