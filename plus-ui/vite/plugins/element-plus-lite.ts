import type { Plugin } from 'vite';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';


/**
 * Element Plus 主入口（element-plus/es/index.mjs）在模块顶层读取 `installer.install`，
 * 导致引用主入口的块（首屏包）必须保留全部组件。这里把对 'element-plus' 的引用换成不含安装器的轻量入口：
 * 具名导出（ElMessage、ElDialog …）完全一致，只去掉默认导出 / install / version。
 * 需要全量安装器的地方（表单设计器）直接引 'element-plus/es/defaults.mjs'。
 */
export default function createElementPlusLite(): Plugin {
  let code: string | undefined;
  // 虚拟入口放在 element-plus/es 目录下：相对导入与它自己的依赖（如 dayjs）按原包解析
  const require = createRequire(import.meta.url);
  const dir = path.dirname(require.resolve('element-plus/package.json'));
  const ID = path.join(dir, 'es/index.ls-lite.mjs');
  return {
    name: 'element-plus-lite',
    enforce: 'pre',
    resolveId(id, importer) {
      // 轻量入口里的依赖按真实主入口解析（文件本身不存在，解析器不能以它为起点）
      if (importer === ID) return this.resolve(id, path.join(dir, 'es/index.mjs'), { skipSelf: true });
      // 'element-plus'，以及 unplugin-auto-import 注入的 'element-plus/es'
      if (!/^element-plus(\/es(\/index\.mjs)?)?$/.test(id)) return null;
      if (importer && importer.includes('/node_modules/element-plus/')) return null;
      return ID;
    },
    load(id) {
      if (id !== ID) return null;
      if (!code) {
        const src = fs.readFileSync(path.join(dir, 'es/index.mjs'), 'utf8');
        const version = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).version;
        code =
          src
            .replace(/^import defaults_default from "\.\/defaults\.mjs";$/m, '')
            .replace(/^const install = defaults_default\.install;$/m, '')
            .replace(/^const version = defaults_default\.version;$/m, `const version = ${JSON.stringify(version)};`)
            .replace(/^var element_plus_default = defaults_default;$/m, '')
            .replace(/ element_plus_default as default,/, '')
            .replace(/ install,/, '') + '\n';
        if (/defaults_default/.test(code)) throw new Error('element-plus-lite：主入口结构变化，请检查 element-plus/es/index.mjs');
      }
      return code;
    }
  };
}
