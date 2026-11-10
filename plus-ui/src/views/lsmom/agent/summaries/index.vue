<template>
  <div class="p-2">
    <el-card shadow="never">
      <SummaryReview :api="summaryApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsSummaryReview">
import { summaryApi } from '@/api/lsmom/summary';
import SummaryReview from './SummaryReview.vue';

/** 摘要确认页：/agent/summaries。 */
</script>
