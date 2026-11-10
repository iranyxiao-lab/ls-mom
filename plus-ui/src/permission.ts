import { to as tos } from 'await-to-js';
import router from './router';
import { startPrefSync } from '@/utils/prefSync';
import { loadUiMode, preferMobile, restoreOfflineUser } from '@/mobile/device';
import NProgress from 'nprogress';
import 'nprogress/nprogress.css';
import { getToken } from '@/utils/auth';
import { isHttp, isPathMatch } from '@/utils/validate';
import { isRelogin } from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus/es';

// 默认模板使用无效的 ARIA 角色（role="bar"），改为合法的进度条角色
NProgress.configure({
  showSpinner: false,
  barSelector: '.bar',
  template: '<div class="bar" role="progressbar" aria-label="页面加载中"><div class="peg"></div></div>'
});
// 错误页不需要登录（断网时取不到用户信息，也要能显示）
const whiteList = ['/login', '/register', '/social-callback', '/register*', '/register/*', '/offline', '/500'];

const isWhiteList = (path: string) => {
  return whiteList.some((pattern) => isPathMatch(pattern, path));
};

router.beforeEach(async (to, from, next) => {
  NProgress.start();
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title as string);
    /* has token*/
    if (to.path === '/login') {
      next({ path: '/' });
      NProgress.done();
    } else if (isWhiteList(to.path)) {
      next();
    } else {
      if (useUserStore().roles.length === 0) {
        isRelogin.show = true;
        // 判断当前用户是否已拉取完user_info信息
        const [err] = await tos(useUserStore().getInfo());
        // 手机版离线打开（已添加到桌面）：取不到用户信息时用上次缓存的身份显示外壳与已缓存的今日任务
        if (err && !navigator.onLine && to.path.startsWith('/m') && restoreOfflineUser()) {
          isRelogin.show = false;
          next();
          NProgress.done();
          return;
        }
        if (err) {
          await useUserStore().logout();
          ElMessage.error(err);
          next({ path: '/' });
        } else {
          isRelogin.show = false;
          // 偏好（主题、布局、收藏等）以服务端为准，先应用再进入页面，避免布局闪变
          await startPrefSync();
          const accessRoutes = await usePermissionStore().generateRoutes();
          // 根据roles权限生成可访问的路由表
          accessRoutes.forEach((route) => {
            if (!isHttp(route.path)) {
              router.addRoute(route); // 动态添加可访问路由表
            }
          });
          // @ts-expect-error hack方法 确保addRoutes已完成
          next({ path: to.path, replace: true, params: to.params, query: to.query, hash: to.hash, name: to.name as string }); // hack方法 确保addRoutes已完成
        }
      } else {
        // 首页按设备宽度与用户类型（或手动选择）进入手机版
        if (to.path === '/index' && !to.query.desktop) {
          const mode = await loadUiMode();
          if (preferMobile(mode, window.innerWidth, useUserStore().roles)) {
            next({ path: '/m', replace: true });
            NProgress.done();
            return;
          }
        }
        next();
      }
    }
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      next();
    } else {
      const redirect = encodeURIComponent(to.fullPath || '/');
      next(`/login?redirect=${redirect}`); // 否则全部重定向到登录页
      NProgress.done();
    }
  }
});

// 页面资源加载失败（断网、发版后旧资源已失效）：显示断网页或出错页，可重试
router.onError((err, to) => {
  const msg = String((err as Error)?.message ?? err);
  if (to.path === '/offline' || to.path === '/500') return;
  if (!/dynamically imported module|Importing a module script failed|Loading chunk|Unable to preload|Failed to fetch/i.test(msg)) return;
  void router.replace({ path: navigator.onLine ? '/500' : '/offline', query: { from: to.fullPath } });
});

router.afterEach(() => {
  NProgress.done();
});
