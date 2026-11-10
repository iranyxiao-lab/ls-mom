import vue from '@vitejs/plugin-vue';
import vueDevTools from 'vite-plugin-vue-devtools';

import createUnoCss from './unocss';
import createAutoImport from './auto-import';
import createComponents from './components';
import createIcons from './icons';
import createSvgIconsPlugin from './svg-icon';
import createCompression from './compression';
import createSetupExtend from './setup-extend';
import createLsTokens from './ls-tokens';
import createEpIconNames from './ep-icon-names';
import createElementPlusLite from './element-plus-lite';
import createPwa from './pwa';
import path from 'path';

export default (viteEnv: any, isBuild = false): [] => {
  const vitePlugins: any = [];
  vitePlugins.push(vue());
  vitePlugins.push(vueDevTools());
  vitePlugins.push(createLsTokens());
  vitePlugins.push(createEpIconNames());
  vitePlugins.push(createElementPlusLite());
  vitePlugins.push(createUnoCss());
  vitePlugins.push(createAutoImport(path));
  vitePlugins.push(createComponents(path));
  vitePlugins.push(createCompression(viteEnv));
  vitePlugins.push(createIcons());
  vitePlugins.push(createSvgIconsPlugin(path));
  vitePlugins.push(createSetupExtend());
  if (isBuild) vitePlugins.push(createPwa(viteEnv));
  return vitePlugins;
};
