import type { Plugin } from 'vite';
import { cssVariables } from '../../src/design/build';

const ID = 'virtual:ls-tokens.css';
const RESOLVED = '\0' + ID;

/** 构建时把设计令牌（src/design/tokens.ts）生成为 CSS 变量；开发时修改令牌会热更新。 */
export default function createLsTokens(): Plugin {
  return {
    name: 'ls-tokens',
    resolveId(id) {
      return id === ID ? RESOLVED : null;
    },
    load(id) {
      if (id !== RESOLVED) return null;
      this.addWatchFile(new URL('../../src/design/tokens.ts', import.meta.url).pathname);
      this.addWatchFile(new URL('../../src/design/build.ts', import.meta.url).pathname);
      return cssVariables();
    },
    handleHotUpdate(ctx) {
      if (/src\/design\/(tokens|build)\.ts$/.test(ctx.file)) {
        const mod = ctx.server.moduleGraph.getModuleById(RESOLVED);
        if (mod) {
          ctx.server.moduleGraph.invalidateModule(mod);
          return [mod, ...ctx.modules];
        }
      }
    }
  };
}
