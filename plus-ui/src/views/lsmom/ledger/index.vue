<template>
  <div class="p-2">
    <el-card shadow="never">
      <LedgerWorkspace
        v-if="concept"
        ref="ws"
        :key="concept"
        :concept="concept"
        :crud="lazyCrudApi"
        :forms="lazyFormApi"
        :flow="lazyFlowProgressApi"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LedgerList">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import { lazyFlowProgressApi } from '@/api/lsmom/flowProgress';
import LedgerWorkspace from './LedgerWorkspace.vue';

/** 通用台账页：/lsmom/ledger/<Concept 编码>，菜单可直接指向该路径。 */
const route = useRoute();
const concept = computed(() => String(route.params.concept || ''));
const ws = ref<InstanceType<typeof LedgerWorkspace>>();

// ?id=<记录 id>：直接打开该记录详情（命令面板、通知、收藏等入口），打开后从地址中去掉
const router = useRouter();
watch(
  () => [route.path, route.query.id] as const,
  async ([path, id]) => {
    if (!id || !path.startsWith('/lsmom/ledger/')) return;
    await nextTick();
    const { id: _drop, ...rest } = route.query;
    await router.replace({ path, query: rest });
    ws.value?.openDetail(Number(id));
  },
  { immediate: true }
);
</script>
