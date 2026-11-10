/**
 * 首页卡片注册表：registerHomeCard({code, title, component, perm?, requiresAnyPath?})。
 * 每张卡片自己取数（Metric 或命名 Query），Pack 也可以注册自己的卡片；角色默认布局与个人调整只保存卡片编码与顺序。
 */
import { markRaw, shallowReactive, type Component } from 'vue';

export interface HomeCardDef {
  /** 稳定编码，例如 ls.home.teamWork */
  code: string;
  title: string;
  /** 一句话说明（编辑布局时显示） */
  description?: string;
  component: Component;
  /** 需要的权限编码 */
  perm?: string;
  /** 依赖的页面：用户菜单里一个都没有时不显示 */
  requiresAnyPath?: string[];
}

const cards = shallowReactive(new Map<string, HomeCardDef>());

export function registerHomeCard(def: HomeCardDef): () => void {
  cards.set(def.code, { ...def, component: markRaw(def.component) });
  return () => cards.delete(def.code);
}

export function homeCard(code: string): HomeCardDef | undefined {
  return cards.get(code);
}

/** 当前用户可用的卡片编码。 */
export function usableCards(perms: string[], menuPaths: Set<string>): string[] {
  const can = (p?: string) => !p || perms.includes('*:*:*') || perms.includes(p);
  return [...cards.values()].filter((c) => can(c.perm) && (!c.requiresAnyPath || c.requiresAnyPath.some((p) => menuPaths.has(p)))).map((c) => c.code);
}
