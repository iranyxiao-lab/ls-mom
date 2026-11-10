import { createWebHistory, createRouter, RouteRecordRaw } from 'vue-router';
import StatusPage from '@/views/error/StatusPage.vue';
/* Layout */
import Layout from '@/layout/index.vue';

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/social-callback',
    hidden: true,
    component: () => import('@/layout/components/SocialCallback/index.vue')
  },
  {
    path: '/login',
    component: () => import('@/views/login.vue'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register.vue'),
    hidden: true
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/error/404.vue'),
    hidden: true
  },
  // 手机版（现场操作工、点检员、维修工）：独立的移动布局，不显示桌面管理菜单
  {
    path: '/m',
    component: () => import('@/mobile/MobileLayout.vue'),
    hidden: true,
    children: [
      { path: '', component: () => import('@/mobile/pages/MobileHome.vue'), meta: { title: '工作台', mobile: true } },
      { path: 'report/:id(\\d+)', component: () => import('@/mobile/pages/MobileReport.vue'), meta: { title: '报工', mobile: true } },
      { path: 'inspect', component: () => import('@/mobile/pages/MobileInspect.vue'), meta: { title: '点检', mobile: true } },
      { path: 'defect', component: () => import('@/mobile/pages/MobileDefect.vue'), meta: { title: '报缺陷', mobile: true } },
      { path: 'me', component: () => import('@/mobile/pages/MobileMe.vue'), meta: { title: '我的', mobile: true } }
    ]
  },
  // 出错页与断网页随主包加载（断网时取不到按需加载的页面资源）
  {
    path: '/500',
    component: StatusPage,
    hidden: true
  },
  {
    path: '/offline',
    component: StatusPage,
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401.vue'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: '/index',
    children: [
      {
        path: '/index',
        component: () => import('@/views/index.vue'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/lsmom',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'ledger/:concept',
        component: () => import('@/views/lsmom/ledger/index.vue'),
        name: 'LedgerList',
        meta: { title: '台账', icon: 'list' }
      },
      {
        path: 'record/:concept/:id(\\d+)',
        component: () => import('@/views/lsmom/ledger/RecordPage.vue'),
        name: 'LedgerRecord',
        meta: { title: '详情', icon: 'list' }
      },
      {
        path: 'form-designer/:code?',
        component: () => import('@/views/lsmom/form-designer/index.vue'),
        name: 'FormDesigner',
        meta: { title: '表单设计器', icon: 'form' }
      },
      {
        path: 'flow-designer/:code?',
        component: () => import('@/views/lsmom/flow-designer/index.vue'),
        name: 'FlowDesigner',
        meta: { title: '流程配置', icon: 'tree' }
      },
      {
        path: 'dashboard/:code',
        component: () => import('@/views/lsmom/dashboard/index.vue'),
        name: 'LsDashboard',
        meta: { title: '看板', icon: 'chart' }
      },
      {
        path: 'object/:type/:id',
        component: () => import('@/views/lsmom/object/index.vue'),
        name: 'LsObjectDetail',
        meta: { title: '对象详情', icon: 'documentation' }
      },
      {
        path: 'workbench',
        component: () => import('@/views/lsmom/workbench/index.vue'),
        name: 'LsWorkbench',
        meta: { title: '协同工作中心', icon: 'job' }
      },
      {
        path: 'apps',
        component: () => import('@/views/lsmom/app-center/index.vue'),
        name: 'LsAppCenter',
        meta: { title: '协同应用中心', icon: 'component' }
      }
    ]
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile',
        component: () => import('@/views/system/user/profile/index.vue'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      },
      {
        path: 'prefs',
        component: () => import('@/views/lsmom/prefs/index.vue'),
        name: 'LsPrefCenter',
        meta: { title: '偏好设置', icon: 'skill' }
      }
    ]
  }
];

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes: RouteRecordRaw[] = [];

/**
 * 创建路由
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.VITE_APP_CONTEXT_PATH),
  routes: constantRoutes,
  // 刷新时，滚动条位置还原
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  }
});

export default router;
