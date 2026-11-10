<template>
  <div class="home" data-testid="home">
    <header class="home-head">
      <div>
        <h1 class="home-hello">{{ greeting }}，{{ user.nickname }}</h1>
        <p class="home-sub">{{ today }}<span v-if="layout?.source === 'personal'"> · 已按你的调整显示</span></p>
      </div>
      <div class="home-tools">
        <template v-if="editing">
          <el-select
            v-if="canManage"
            v-model="roleKey"
            placeholder="设为角色默认…"
            clearable
            style="width: 180px"
            aria-label="选择角色"
            data-testid="home-role"
          >
            <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
          </el-select>
          <el-button v-if="canManage" :disabled="!roleKey" data-testid="home-save-role" @click="saveRole">设为该角色默认</el-button>
          <el-button data-testid="home-reset" @click="reset">恢复默认</el-button>
          <el-button type="primary" data-testid="home-done" @click="finish">完成</el-button>
        </template>
        <el-button v-else data-testid="home-edit" @click="editing = true">调整首页</el-button>
      </div>
    </header>

    <LsSkeleton v-if="!layout" variant="card" :rows="4" />
    <div v-else class="home-grid">
      <div v-for="(code, i) in merged.shown" :key="code" class="home-slot" :data-card="code">
        <component :is="homeCard(code)!.component" />
        <div v-if="editing" class="slot-tools" role="group" :aria-label="`调整「${homeCard(code)!.title}」`">
          <el-button size="small" :disabled="i === 0" :aria-label="`上移 ${homeCard(code)!.title}`" data-testid="card-up" @click="shift(code, -1)"
            >上移</el-button
          >
          <el-button
            size="small"
            :disabled="i === merged.shown.length - 1"
            :aria-label="`下移 ${homeCard(code)!.title}`"
            data-testid="card-down"
            @click="shift(code, 1)"
          >
            下移
          </el-button>
          <el-button size="small" type="danger" plain :aria-label="`隐藏 ${homeCard(code)!.title}`" data-testid="card-hide" @click="hide(code)">
            隐藏
          </el-button>
        </div>
      </div>
      <LsEmpty v-if="!merged.shown.length" title="首页没有卡片" description="点「调整首页」添加卡片，或「恢复默认」" />
    </div>

    <section v-if="editing && (merged.hidden.length || merged.available.length)" class="home-more" aria-label="可添加的卡片">
      <h2>可添加的卡片</h2>
      <ul>
        <li v-for="code in [...merged.hidden, ...merged.available]" :key="code">
          <span>{{ homeCard(code)!.title }}</span>
          <span class="desc">{{ homeCard(code)!.description }}</span>
          <el-button size="small" type="primary" plain :data-testid="`card-add-${code}`" @click="add(code)">添加</el-button>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts" name="Index">
/**
 * 首页：按角色默认布局显示注册的卡片（我的待办、今日工作、异常、关键指标、快捷入口），每张卡片可一键进入对应列表。
 * 用户可隐藏、添加卡片并调整顺序（保存在服务端，下次登录保持），「恢复默认」回到角色布局；管理员可把当前布局设为某角色的默认布局。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { LsEmpty, LsSkeleton } from '@/components/LsState';
import { useUserStore } from '@/store/modules/user';
import { homeApi, type HomeLayout } from '@/api/lsmom/uiHome';
import { registerBuiltinHomeCards } from '@/home/builtin';
import { homeCard, usableCards } from '@/home/registry';
import { useUndoable } from '@/utils/undoable';
import { mergeLayout, move, toLayout, type Merged } from '@/home/layout';
import { useMenuPaths } from '@/home/useMenuPaths';

registerBuiltinHomeCards();
const user = useUserStore();
const menu = useMenuPaths();
const layout = ref<HomeLayout>();
const draft = ref<Merged | null>(null);
const editing = ref(false);
const canManage = computed(() => user.permissions.includes('*:*:*') || user.permissions.includes('lsmom:ui:home:manage'));
const roles = ref<{ roleKey: string; roleName: string }[]>([]);
const roleKey = ref<string>();

const usable = computed(() => usableCards(user.permissions, menu.value));
const merged = computed<Merged>(() => draft.value ?? mergeLayout(layout.value?.cards ?? [], usable.value));

const h = new Date().getHours();
const greeting = h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好';
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });

async function load() {
  layout.value = await homeApi.get();
  draft.value = null;
}
const edit = (fn: (m: Merged) => Merged) => {
  draft.value = fn({ ...merged.value });
};
const shift = (code: string, d: -1 | 1) => edit((m) => ({ ...m, shown: move(m.shown, code, d) }));
const undoable = useUndoable();
/** 隐藏卡片是低风险、可逆的：不确认，提示里 5 秒内可撤销（回到原位置） */
function hide(code: string) {
  const m = merged.value;
  const before: Merged = { ...m, shown: [...m.shown], hidden: [...m.hidden], available: [...m.available] };
  edit((m) => ({ ...m, shown: m.shown.filter((c) => c !== code), hidden: [...m.hidden, code] }));
  undoable.offer(`已隐藏「${homeCard(code)?.title ?? code}」`, () => {
    draft.value = before;
  });
}
const add = (code: string) =>
  edit((m) => ({ shown: [...m.shown, code], hidden: m.hidden.filter((c) => c !== code), available: m.available.filter((c) => c !== code) }));

async function finish() {
  if (draft.value) {
    await homeApi.saveMine(toLayout(draft.value));
    ElMessage.success('首页布局已保存');
    await load();
  }
  editing.value = false;
}
async function reset() {
  await homeApi.resetMine();
  await load();
  editing.value = false;
  ElMessage.success('已恢复为角色默认布局');
}
async function saveRole() {
  if (!roleKey.value) return;
  await homeApi.saveRole(roleKey.value, toLayout(merged.value));
  ElMessage.success('已设为该角色的默认首页');
}

onMounted(async () => {
  await load();
  if (canManage.value) roles.value = await homeApi.roles().catch(() => []);
});
</script>

<style scoped lang="scss">
.home {
  padding: var(--ls-space-4);
}
.home-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ls-space-3);
  margin-bottom: var(--ls-space-4);
}
.home-hello {
  margin: 0;
  font-size: 22px;
}
.home-sub {
  margin: var(--ls-space-1) 0 0;
  color: var(--el-text-color-secondary);
}
.home-tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ls-space-2);
}
.home-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
  gap: var(--ls-space-4);
}
.home-slot {
  display: flex;
  flex-direction: column;
  gap: var(--ls-space-2);
  > :first-child {
    flex: 1;
  }
}
.slot-tools {
  display: flex;
  gap: var(--ls-space-2);
}
.home-more {
  margin-top: var(--ls-space-6);
  h2 {
    font-size: 16px;
  }
  ul {
    list-style: none;
    padding: 0;
  }
  li {
    display: flex;
    align-items: center;
    gap: var(--ls-space-3);
    min-height: 40px;
  }
  .desc {
    flex: 1;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}
</style>
