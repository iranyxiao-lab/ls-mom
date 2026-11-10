/**
 * 命令面板的动作：内置动作＋页面或 Pack 注册的动作（registerCommand）。
 * 动作带权限或依赖的页面地址时，只有有权限、能打开该页面的用户才看得到。
 */
import { shallowReactive } from 'vue';

export interface Command {
  id: string;
  title: string;
  /** 额外的搜索词（同义词） */
  keywords?: string;
  icon?: string;
  /** 需要的权限编码 */
  perm?: string;
  /** 依赖的页面：该页面不在用户菜单中时不显示 */
  requiresPath?: string;
  run: () => unknown;
}

const registry = shallowReactive(new Map<string, Command>());

/** 注册动作，返回注销函数。 */
export function registerCommand(c: Command): () => void {
  registry.set(c.id, c);
  return () => registry.delete(c.id);
}

/** 当前用户可用的动作。 */
export function availableCommands(perms: string[], menuPaths: Set<string>): Command[] {
  const can = (p?: string) => !p || perms.includes('*:*:*') || perms.includes(p);
  return [...registry.values()].filter((c) => can(c.perm) && (!c.requiresPath || menuPaths.has(c.requiresPath)));
}
