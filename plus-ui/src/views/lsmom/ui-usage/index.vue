<template>
  <div class="app-container">
    <UsageReport :api="uiUsageApi" :can-manage="canManage" />
  </div>
</template>

<script setup lang="ts" name="UiUsage">
import { computed } from 'vue';
import UsageReport from './UsageReport.vue';
import { uiUsageApi } from '@/api/lsmom/uiUsage';
import { useUserStore } from '@/store/modules/user';

const user = useUserStore();
const canManage = computed(() => user.permissions.includes('*:*:*') || user.permissions.includes('lsmom:ui:usage:manage'));
</script>
