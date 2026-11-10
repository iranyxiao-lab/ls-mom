import { VitePWA } from 'vite-plugin-pwa';

/**
 * PWA（手机版可添加到桌面、全屏打开）：预缓存应用外壳（index.html、入口与手机版页面），
 * 其余带哈希的静态资源首次使用后缓存（CacheFirst），离线时外壳与已访问过的页面可用；接口不缓存（今日任务由页面自己缓存）。
 */
export default function createPwa(viteEnv: Record<string, string>) {
  const base = viteEnv.VITE_APP_CONTEXT_PATH || '/';
  const api = viteEnv.VITE_APP_BASE_API || '/prod-api';
  const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return VitePWA({
    registerType: 'autoUpdate',
    injectRegister: 'script-defer',
    includeAssets: ['favicon.ico'],
    manifest: {
      name: 'LS-MOM 现场',
      short_name: 'LS-MOM',
      description: '今日任务、报工、点检与报缺陷',
      lang: 'zh-CN',
      start_url: `${base}m`,
      scope: base,
      display: 'standalone',
      orientation: 'portrait',
      background_color: '#f5f6f8',
      theme_color: '#1677ff',
      icons: [
        { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
        { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
        { src: 'pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
      ]
    },
    workbox: {
      // 外壳：index.html、入口脚本与样式、手机版页面
      globPatterns: ['index.html', 'assets/index-*.css', 'assets/Mobile*.{js,css}', 'pwa-*.png', 'favicon.ico'],
      navigateFallback: `${base}index.html`,
      navigateFallbackDenylist: [new RegExp('^' + esc(api)), /^\/(resource|api|actuator)\//],
      runtimeCaching: [
        {
          urlPattern: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith(`${base}assets/`),
          handler: 'CacheFirst',
          options: { cacheName: 'ls-assets', expiration: { maxEntries: 600, maxAgeSeconds: 30 * 24 * 3600 } }
        }
      ]
    }
  });
}
