/**
 * 首页卡片布局的纯函数：把服务端布局（编码、顺序、是否隐藏）与已注册且有权查看的卡片合并。
 * 布局里有但未注册（或无权限）的卡片不显示；已注册但布局里没有的卡片列为"可添加"。
 */
export interface LayoutCard {
  code: string;
  hidden?: boolean;
}

export interface Merged {
  /** 显示的卡片（按布局顺序） */
  shown: string[];
  /** 已隐藏的卡片 */
  hidden: string[];
  /** 可添加的卡片（布局里没有） */
  available: string[];
}

export function mergeLayout(layout: LayoutCard[], usable: string[]): Merged {
  const ok = new Set(usable);
  const seen = new Set<string>();
  const shown: string[] = [];
  const hidden: string[] = [];
  for (const c of layout) {
    if (!ok.has(c.code) || seen.has(c.code)) continue;
    seen.add(c.code);
    (c.hidden ? hidden : shown).push(c.code);
  }
  return { shown, hidden, available: usable.filter((c) => !seen.has(c)) };
}

/** 编辑结果写回布局：显示的在前（按顺序），隐藏的随后标记 hidden；未添加的不写。 */
export function toLayout(m: Merged): LayoutCard[] {
  return [...m.shown.map((code) => ({ code })), ...m.hidden.map((code) => ({ code, hidden: true }))];
}

/** 上移 / 下移一位。 */
export function move(list: string[], code: string, delta: -1 | 1): string[] {
  const i = list.indexOf(code);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= list.length) return list;
  const out = [...list];
  [out[i], out[j]] = [out[j], out[i]];
  return out;
}
