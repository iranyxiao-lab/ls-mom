import type { Plugin } from 'vite';
import { createRequire } from 'node:module';

const ID = 'virtual:ep-icon-names';
const RESOLVED = '\0' + ID;

/** 构建时取得 Element Plus 图标名清单，供全局异步注册（图标包本身按需加载）。 */
export default function createEpIconNames(): Plugin {
  return {
    name: 'ep-icon-names',
    resolveId: (id) => (id === ID ? RESOLVED : null),
    load(id) {
      if (id !== RESOLVED) return null;
      const icons = createRequire(import.meta.url)('@element-plus/icons-vue');
      return `export default ${JSON.stringify(Object.keys(icons).filter((k) => /^[A-Z]/.test(k)))};`;
    }
  };
}
