import { createApp } from 'vue';
// global css
import 'virtual:uno.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import '@/assets/styles/index.scss';
// 设计令牌（--ls-* 及 Element Plus / Vant 映射），放在组件库样式之后
import 'virtual:ls-tokens.css';
import '@/design/base.css';

// App、router、store
import App from './App.vue';
import store from './store';
import router from './router';

// 自定义指令
import directive from './directive';

// 注册插件
import plugins from './plugins/index'; // plugins

// 代码高亮（highlight.js）只在代码生成预览页按需加载；vxe-table 由用到的组件按需引入（src/plugins/vxe.ts）

// svg图标
import 'virtual:svg-icons-register';
import ElementIcons from '@/plugins/svgicon';

// permission control
import './permission';

// 国际化
import i18n from '@/lang/index';


// 修改 el-dialog 默认点击遮照为不关闭
import { ElDialog } from 'element-plus';
ElDialog.props.closeOnClickModal.default = false;

// 明暗主题与密度：首屏已由 index.html 设置，这里接管后续切换与跟随系统
import { useDensity, useThemeMode } from '@/design/theme';
useThemeMode();
useDensity();

const app = createApp(App);

app.use(ElementIcons);
app.use(router);
app.use(store);
app.use(i18n);
app.use(plugins);
// 自定义指令
directive(app);

app.mount('#app');

// 无障碍兜底：没有可读名称的表单控件按表单项标签或占位文字补 aria-label
import { installAutoLabel } from '@/design/a11y-autolabel';
installAutoLabel();
