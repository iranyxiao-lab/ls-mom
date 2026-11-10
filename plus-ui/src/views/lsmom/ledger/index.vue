<template>
  <div class="p-2">
    <el-card shadow="never">
      <LedgerWorkspace v-if="concept" :key="concept" :concept="concept" :crud="lazyCrudApi" :forms="lazyFormApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LedgerList">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import LedgerWorkspace from './LedgerWorkspace.vue';

/** 通用台账页：/lsmom/ledger/<Concept 编码>，菜单可直接指向该路径。 */
const route = useRoute();
const concept = computed(() => String(route.params.concept || ''));
</script>
