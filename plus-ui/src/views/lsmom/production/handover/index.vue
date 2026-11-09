<template>
  <div class="p-2">
    <el-card shadow="never">
      <HandoverPage :api="productionApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsProductionHandover">
import { productionApi } from '@/api/lsmom/production';
import HandoverPage from './HandoverPage.vue';

/** 轻生产：交接班（菜单 /production/handover）。 */
</script>
