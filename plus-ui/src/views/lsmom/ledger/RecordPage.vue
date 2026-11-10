<template>
  <div class="p-2">
    <el-card shadow="never" data-testid="record-page">
      <template #header>
        <div class="record-page__head">
          <el-button link type="primary" data-testid="record-back" @click="router.push(`/lsmom/ledger/${encodeURIComponent(concept)}`)"
            >返回列表</el-button
          >
          <span>{{ title }}</span>
        </div>
      </template>
      <RecordView
        v-if="concept && id"
        :key="`${concept}:${id}`"
        :concept="concept"
        :id="id"
        :crud="lazyCrudApi"
        :forms="lazyFormApi"
        :flow="lazyFlowProgressApi"
        @loaded="onLoaded"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LedgerRecord">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import { lazyFlowProgressApi } from '@/api/lsmom/flowProgress';
import RecordView from './RecordView.vue';
import { recordLabel } from './ledgerModel';

/** 单条记录页：/lsmom/record/<Concept>/<id>，抽屉里"在新页签打开"进入，与抽屉共用 RecordView。 */
const route = useRoute();
const router = useRouter();
const concept = computed(() => String(route.params.concept || ''));
const id = computed(() => Number(route.params.id || 0));
const title = ref('详情');
function onLoaded(r: Record<string, any>) {
  title.value = `详情 · ${recordLabel(r, null)}`;
}
</script>

<style scoped>
.record-page__head {
  display: flex;
  gap: 12px;
  align-items: center;
}
</style>
