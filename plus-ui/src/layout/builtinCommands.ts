/** 命令面板内置动作：界面切换、页签操作，以及常用业务动作的入口（依赖页面在用户菜单中才显示）。 */
import router from '@/router';
import tab from '@/plugins/tab';
import { useDensity, useThemeMode } from '@/design/theme';
import { registerCommand } from './commands';

let done = false;

export function registerBuiltinCommands(): void {
  if (done) return;
  done = true;
  registerCommand({
    id: 'ui.toggleTheme',
    title: '切换深色 / 浅色主题',
    keywords: '暗色 夜间 主题 dark',
    icon: 'theme',
    run: () => useThemeMode().toggle()
  });
  registerCommand({
    id: 'ui.toggleDensity',
    title: '切换紧凑 / 舒适密度',
    keywords: '密度 行高 紧凑 宽松',
    icon: 'size',
    run: () => {
      const { pref } = useDensity();
      pref.value = pref.value === 'compact' ? 'comfortable' : 'compact';
    }
  });
  registerCommand({ id: 'tab.refresh', title: '刷新当前页面', keywords: '重新加载', icon: 'tab', run: () => tab.refreshPage() });
  registerCommand({ id: 'tab.closeOthers', title: '关闭其他页签', keywords: '页签 标签', icon: 'tab', run: () => tab.closeOtherPage() });
  registerCommand({
    id: 'quality.nc.new',
    title: '登记不符合项',
    keywords: '质量 问题 缺陷 新建',
    icon: 'edit',
    requiresPath: '/quality/nc',
    run: () => router.push({ path: '/quality/nc', query: { action: 'new' } })
  });
}
