/**
 * vxe-table 按需加载：只有用到表格编辑器的页面才引入（不进入首屏包）。
 * 用法：在组件中 `import { VxeTable, VxeColumn } from '@/plugins/vxe'`。
 */
import { VxeUI } from 'vxe-table';
import 'vxe-table/lib/style.css';

VxeUI.setConfig({ zIndex: 999999 });

export { VxeTable, VxeColumn, VxeColgroup, VxeToolbar } from 'vxe-table';
export { VxeUI };
