<template>
  <div class="p-2">
    <el-card shadow="never" data-testid="object-detail">
      <LsSkeleton v-if="loading" variant="form" :rows="6" />
      <LsError
        v-else-if="error"
        title="打不开这个对象"
        :reason="error"
        next-step="确认编号是否正确；不在你的数据范围内的对象不会显示。"
        retryable
        @retry="load"
      />
      <template v-else-if="data">
        <div class="obj-head">
          <h2 class="obj-title" data-testid="object-title">
            <span class="obj-code">{{ data.code }}</span
            >{{ type === 'work' ? data.title : data.name }}
          </h2>
          <LsStatusTag v-if="type === 'work'" :status="data.status" :label="WORK_STATUS[data.status] ?? data.status" />
          <el-button link :aria-label="fav ? '取消收藏' : '收藏'" data-testid="object-star" @click="toggleFav">
            <el-icon :class="{ 'is-fav': fav }"><StarFilled v-if="fav" /><Star v-else /></el-icon>{{ fav ? '已收藏' : '收藏' }}
          </el-button>
        </div>
        <el-descriptions v-if="type === 'work'" :column="2" border>
          <el-descriptions-item label="工作类型">{{ data.type }}</el-descriptions-item>
          <el-descriptions-item label="所属组织">{{ data.orgUnitName }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ (data.assignees ?? []).join('、') || '未分派' }}</el-descriptions-item>
          <el-descriptions-item label="进度">{{ data.progress ?? 0 }}%</el-descriptions-item>
          <el-descriptions-item label="计划开始">{{ fmt(data.plannedStart) }}</el-descriptions-item>
          <el-descriptions-item label="计划结束">{{ fmt(data.plannedEnd) }}</el-descriptions-item>
          <el-descriptions-item label="实际开始">{{ fmt(data.actualStart) }}</el-descriptions-item>
          <el-descriptions-item label="实际结束">{{ fmt(data.actualEnd) }}</el-descriptions-item>
          <el-descriptions-item v-if="data.blockReason" label="阻塞原因" :span="2">{{ data.blockReason }}</el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">{{ data.description || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-else :column="2" border>
          <el-descriptions-item label="人员类型">{{ data.partyType === 'OUTSOURCE' ? '外协' : '员工' }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ data.company || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属组织" :span="2">
            {{ (data.orgs ?? []).map((o: any) => o.name + (o.role === 'LEADER' ? '（负责人）' : '')).join('、') || '—' }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsObjectDetail">
/** 对象详情（命令面板直达）：工作、人员。只读，数据域外的对象显示为打不开。 */
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Star, StarFilled } from '@element-plus/icons-vue';
import { LsError, LsSkeleton, LsStatusTag } from '@/components/LsState';
import { getParty, getWork } from '@/api/lsmom/uiSearch';
import { WORK_STATUS } from '@/api/lsmom/productionModel';
import { useNavHistoryStore } from '@/store/modules/navHistory';
import { useTagsViewStore } from '@/store/modules/tagsView';

const route = useRoute();
const history = useNavHistoryStore();
const type = computed(() => String(route.params.type));
const id = computed(() => String(route.params.id));
const data = ref<any>(null);
const loading = ref(false);
const error = ref('');

const fmt = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—');
const entry = () => ({
  kind: 'object' as const,
  path: route.fullPath,
  title: `${data.value.code} ${type.value === 'work' ? data.value.title : data.value.name}`,
  sub: type.value === 'work' ? '工作' : '人员',
  icon: type.value === 'work' ? 'job' : 'user'
});
const fav = computed(() => history.isFavorite(route.fullPath));
const toggleFav = () => data.value && history.toggleFavorite(entry());

async function load() {
  if (!['work', 'party'].includes(type.value)) {
    error.value = '不支持的对象类型';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    data.value = await (type.value === 'work' ? getWork(id.value) : getParty(id.value));
    const e = entry();
    history.visit(e);
    // 页签标题显示对象编号与名称
    useTagsViewStore().updateVisitedView({ ...route, title: e.title, meta: { ...route.meta, title: e.title } } as any);
  } catch (e: any) {
    data.value = null;
    error.value = e?.message || '对象不存在或不在你的数据范围内';
  } finally {
    loading.value = false;
  }
}
watch(
  () => route.fullPath,
  (p) => p.startsWith('/lsmom/object/') && load(),
  { immediate: true }
);
</script>

<style scoped>
.obj-head {
  display: flex;
  align-items: center;
  gap: var(--ls-space-3);
  margin-bottom: var(--ls-space-4);
}
.obj-title {
  margin: 0;
  font-size: var(--ls-font-size-xl, 20px);
}
.obj-code {
  font-family: var(--ls-font-mono, monospace);
  color: var(--el-color-primary);
  margin-right: var(--ls-space-2);
}
.is-fav {
  color: var(--el-color-warning);
}
</style>
