<template>
  <el-dialog
    v-model="visible"
    :show-close="false"
    width="640px"
    top="10vh"
    class="ls-palette"
    append-to-body
    destroy-on-close
    aria-label="命令面板"
    data-testid="command-palette"
    @opened="focus"
  >
    <div class="ls-palette__box" @keydown="onKey">
      <el-input
        ref="inputRef"
        v-model="query"
        size="large"
        placeholder="搜索菜单、单据编号、设备、人员或动作（支持拼音首字母）"
        clearable
        data-testid="palette-input"
        aria-controls="ls-palette-list"
        :aria-activedescendant="items[active] ? `ls-palette-item-${active}` : undefined"
      >
        <template #prefix><svg-icon icon-class="search" /></template>
      </el-input>
      <div id="ls-palette-list" ref="listRef" class="ls-palette__list" role="listbox" aria-label="搜索结果">
        <template v-for="(it, i) in items" :key="it.key">
          <div v-if="it.section" class="ls-palette__section">{{ it.section }}</div>
          <div
            :id="`ls-palette-item-${i}`"
            role="option"
            :aria-selected="i === active"
            :class="['ls-palette__item', { 'is-active': i === active }]"
            :data-testid="`palette-item-${it.kind}`"
            :data-path="it.path"
            @mousemove="active = i"
            @click="choose(it)"
          >
            <svg-icon :icon-class="it.icon || 'link'" class="ls-palette__icon" />
            <span class="ls-palette__title">
              <span v-if="it.code" class="ls-palette__code">{{ it.code }}</span>
              {{ it.title }}
            </span>
            <span class="ls-palette__sub">{{ it.sub }}</span>
            <!-- 选项内不能再放可聚焦的按钮（无障碍：嵌套交互），键盘用 Ctrl/⌘+D 收藏 -->
            <span
              v-if="it.path"
              class="ls-palette__star"
              :title="history.isFavorite(it.path) ? '取消收藏（Ctrl+D）' : '收藏（Ctrl+D）'"
              aria-hidden="true"
              data-testid="palette-star"
              @click.stop="star(it)"
            >
              <el-icon :class="{ 'is-fav': history.isFavorite(it.path) }"><StarFilled v-if="history.isFavorite(it.path)" /><Star v-else /></el-icon>
            </span>
          </div>
        </template>
        <div v-if="!items.length" class="ls-palette__empty" data-testid="palette-empty">
          {{ loading ? '正在搜索…' : query.trim() ? '没有找到匹配的结果，换个关键词或用拼音首字母试试' : '还没有最近访问或收藏的内容' }}
        </div>
      </div>
      <div v-if="hotkeyHints" class="ls-palette__foot" data-testid="palette-hints">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span><span><kbd>Enter</kbd> 打开</span
        ><span
          ><kbd>{{ isMac ? '⌘' : 'Ctrl' }}</kbd
          ><kbd>D</kbd> 收藏</span
        ><span><kbd>Esc</kbd> 关闭</span>
        <span>{{ isMac ? '⌘' : 'Ctrl' }}+K 随时打开</span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 命令面板（Ctrl/⌘+K 或顶栏搜索）：菜单与动作在本地按拼音匹配，单据与对象调用后端全局搜索（已按权限与数据域过滤）。
 * 空输入时显示收藏和最近访问。编号完全一致的对象排在首位，回车直接打开详情。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter, type RouteRecordRaw } from 'vue-router';
import { Star, StarFilled } from '@element-plus/icons-vue';
import { usePermissionStore } from '@/store/modules/permission';
import { useUserStore } from '@/store/modules/user';
import { useNavHistoryStore, type NavEntry } from '@/store/modules/navHistory';
import { FAVORITES_GROUP, useGroupedMenus } from '@/layout/useGroupedMenus';
import { availableCommands, type Command } from '@/layout/commands';
import { registerBuiltinCommands } from '@/layout/builtinCommands';
import { loadPinyin, rankBy } from '@/utils/pinyinMatch';
import { hotkeyHints } from '@/utils/prefSync';
import { globalSearch, objectPath, WORK, PARTY, type SearchHit } from '@/api/lsmom/uiSearch';

interface Item {
  key: string;
  kind: 'menu' | 'action' | 'object' | 'recent' | 'favorite';
  title: string;
  code?: string | null;
  sub?: string;
  icon?: string;
  path?: string;
  section?: string;
  run?: () => unknown;
}

const router = useRouter();
const history = useNavHistoryStore();
const user = useUserStore();
const permissionStore = usePermissionStore();
const grouped = useGroupedMenus(() => permissionStore.getDefaultRoutes());

const visible = ref(false);
const query = ref('');
const active = ref(0);
const loading = ref(false);
const hits = ref<SearchHit[]>([]);
const inputRef = ref();
const listRef = ref<HTMLElement>();
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

registerBuiltinCommands();

const join = (base: string, p: string) => (p.startsWith('/') ? p : `${base.replace(/\/$/, '')}/${p}`).replace(/\/+/g, '/');
/** 菜单叶子页面：完整路径与"分组 / 模块"路径。 */
const menus = computed(() => {
  const out: { path: string; title: string; trail: string; icon?: string }[] = [];
  const walk = (rs: RouteRecordRaw[], base: string, trail: string[]) => {
    for (const r of rs) {
      // 收藏分组里的页面在各自的分组中已经有了
      if ((r as any).hidden || r.path === FAVORITES_GROUP) continue;
      const full = join(base, r.path);
      const title = r.meta?.title as string | undefined;
      const kids = (r.children ?? []).filter((c) => !(c as any).hidden);
      if (kids.length) walk(kids, r.path.startsWith('/menu-group') ? '' : full, title ? [...trail, title] : trail);
      else if (title && !/^https?:/.test(r.path)) out.push({ path: full, title, trail: trail.join(' / '), icon: r.meta?.icon as string });
    }
  };
  walk(grouped.value, '', []);
  return out;
});
const menuTitle = (path: string) => menus.value.find((m) => m.path === path);

const objectSub = (h: SearchHit) =>
  h.object_type === WORK ? '工作' : h.object_type === PARTY ? `人员${h.subtitle ? ' · ' + h.subtitle : ''}` : h.subtitle || '台账记录';
const objectIcon = (h: SearchHit) => (h.object_type === WORK ? 'job' : h.object_type === PARTY ? 'user' : 'list');
const asObject = (h: SearchHit): Item => ({
  key: `o:${h.object_type}:${h.object_id}`,
  kind: 'object',
  title: h.title,
  code: h.code,
  sub: objectSub(h),
  icon: objectIcon(h),
  path: objectPath(h.object_type, h.object_id)
});
const asEntry = (e: NavEntry, kind: 'recent' | 'favorite'): Item => ({
  key: `${kind}:${e.path}`,
  kind,
  title: e.title,
  sub: e.sub,
  icon: e.icon || (e.kind === 'object' ? 'documentation' : 'link'),
  path: e.path
});

// 拼音字典加载完成后重新计算匹配结果
const pinyinTick = ref(0);
const items = computed<Item[]>(() => {
  void pinyinTick.value;
  const q = query.value.trim();
  const withSection = (list: Item[], section: string) => list.map((x, i) => (i === 0 ? { ...x, section } : x));
  if (!q) {
    const favs = history.favorites.map((e) => asEntry(e, 'favorite'));
    const favPaths = new Set(favs.map((f) => f.path));
    const recent = history.recent.filter((e) => !favPaths.has(e.path)).map((e) => asEntry(e, 'recent'));
    return [...withSection(favs, '收藏'), ...withSection(recent, '最近访问')];
  }
  const menuItems = rankBy(menus.value, (m) => `${m.title}`, q)
    .slice(0, 8)
    .map<Item>((m) => ({ key: `m:${m.path}`, kind: 'menu', title: m.title, sub: m.trail, icon: m.icon, path: m.path }));
  const perms = user.permissions;
  const actions = rankBy(availableCommands(perms, new Set(menus.value.map((m) => m.path))), (c: Command) => `${c.title} ${c.keywords ?? ''}`, q)
    .slice(0, 5)
    .map<Item>((c) => ({ key: `a:${c.id}`, kind: 'action', title: c.title, sub: '动作', icon: c.icon || 'guide', run: c.run }));
  const objects = hits.value.map(asObject);
  // 编号完全一致（直达单据）时对象排第一组，否则菜单在前
  const exact = hits.value[0]?.rank === 0;
  const groups: [Item[], string][] = exact
    ? [
        [objects, '单据与对象'],
        [menuItems, '菜单'],
        [actions, '动作']
      ]
    : [
        [menuItems, '菜单'],
        [actions, '动作'],
        [objects, '单据与对象']
      ];
  return groups.flatMap(([list, s]) => withSection(list, s));
});

let timer: ReturnType<typeof setTimeout> | undefined;
let seq = 0;
watch(query, (q) => {
  active.value = 0;
  clearTimeout(timer);
  const kw = q.trim();
  if (!kw) {
    hits.value = [];
    loading.value = false;
    return;
  }
  loading.value = true;
  timer = setTimeout(async () => {
    const mine = ++seq;
    try {
      const r = await globalSearch(kw, 10);
      if (mine === seq) hits.value = r;
    } catch {
      if (mine === seq) hits.value = [];
    } finally {
      if (mine === seq) loading.value = false;
    }
  }, 180);
});
watch(items, () => {
  if (active.value >= items.value.length) active.value = Math.max(0, items.value.length - 1);
});

function open() {
  void loadPinyin().then(() => pinyinTick.value++);
  query.value = '';
  hits.value = [];
  active.value = 0;
  visible.value = true;
}
function close() {
  visible.value = false;
}
function focus() {
  inputRef.value?.focus();
}

function entryOf(it: Item): Omit<NavEntry, 'at'> | null {
  if (!it.path) return null;
  if (it.kind === 'object')
    return { kind: 'object', path: it.path, title: it.code ? `${it.code} ${it.title}` : it.title, sub: it.sub, icon: it.icon };
  if (it.kind === 'menu') return { kind: 'page', path: it.path, title: it.title, sub: it.sub, icon: it.icon };
  return {
    kind: it.path.startsWith('/lsmom/object') || it.path.includes('?id=') ? 'object' : 'page',
    path: it.path,
    title: it.title,
    sub: it.sub,
    icon: it.icon
  };
}

async function choose(it: Item | undefined) {
  if (!it) return;
  close();
  if (it.run) {
    await it.run();
    return;
  }
  if (!it.path) return;
  const e = entryOf(it);
  if (e && e.kind === 'object') history.visit(e);
  await router.push(it.path);
}
function star(it: Item) {
  const e = entryOf(it);
  if (e) history.toggleFavorite(e);
}

function onKey(e: KeyboardEvent) {
  const n = items.value.length;
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    if (!n) return;
    active.value = (active.value + (e.key === 'ArrowDown' ? 1 : n - 1)) % n;
    nextTick(() => listRef.value?.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' }));
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd') {
    // 收藏 / 取消收藏当前选中项
    e.preventDefault();
    const it = items.value[active.value];
    if (!it) return;
    star(it);
    // 收藏后该项移到收藏分组（或移回最近访问），选中跟着它走
    const i = items.value.findIndex((x) => x.path === it.path);
    if (i >= 0) active.value = i;
  } else if (e.key === 'Enter' && !e.isComposing) {
    e.preventDefault();
    // 输入后还在等搜索结果时，回车等结果回来再打开首项（按编号直达）
    if (loading.value) {
      const stop = watch(loading, (l) => {
        if (!l) {
          stop();
          void choose(items.value[0]);
        }
      });
      return;
    }
    void choose(items.value[active.value]);
  }
}

/** 全局快捷键：Ctrl/⌘+K 打开（再按一次关闭）。 */
function onGlobalKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    visible.value ? close() : open();
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey));

/** 访问页面时记入最近访问（标题取菜单名称）。 */
const stopAfter = router.afterEach((to) => {
  // 带动作参数的跳转（?action=new）随后会去掉参数再记录一次，这里不记
  if (to.query.action) return;
  const m = menuTitle(to.path);
  if (m) history.visit({ kind: 'page', path: to.fullPath, title: m.title, sub: m.trail, icon: m.icon });
});
onBeforeUnmount(stopAfter);

defineExpose({ open, close });
</script>

<style lang="scss">
.ls-palette {
  border-radius: var(--ls-radius-lg, 12px);
  overflow: hidden;
  max-width: calc(100vw - 32px);
  .el-dialog__header {
    display: none;
  }
  .el-dialog__body {
    padding: var(--ls-space-3, 12px);
  }
}
.ls-palette__list {
  max-height: min(60vh, 480px);
  overflow-y: auto;
  margin-top: var(--ls-space-2, 8px);
}
.ls-palette__section {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: var(--ls-space-2, 8px) var(--ls-space-2, 8px) 4px;
}
.ls-palette__item {
  display: flex;
  align-items: center;
  gap: var(--ls-space-2, 8px);
  min-height: 40px;
  padding: 0 var(--ls-space-2, 8px);
  border-radius: var(--ls-radius-md, 6px);
  cursor: pointer;
  &.is-active {
    background: var(--el-color-primary-light-9);
  }
}
.ls-palette__icon {
  flex: none;
  color: var(--el-text-color-secondary);
}
.ls-palette__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ls-palette__code {
  font-family: var(--ls-font-mono, monospace);
  color: var(--el-color-primary);
  margin-right: 6px;
}
.ls-palette__sub {
  flex: none;
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ls-palette__star {
  display: inline-flex;
  padding: 4px;
  cursor: pointer;
  color: var(--el-text-color-secondary);
}
.ls-palette__star .is-fav {
  color: var(--el-color-warning);
}
.ls-palette__empty {
  padding: var(--ls-space-6, 24px) var(--ls-space-2, 8px);
  text-align: center;
  color: var(--el-text-color-secondary);
}
.ls-palette__foot {
  display: flex;
  gap: var(--ls-space-4, 16px);
  flex-wrap: wrap;
  margin-top: var(--ls-space-2, 8px);
  padding-top: var(--ls-space-2, 8px);
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  kbd {
    font-family: inherit;
    border: 1px solid var(--el-border-color);
    border-radius: 4px;
    padding: 0 4px;
    margin-right: 2px;
  }
}
</style>
