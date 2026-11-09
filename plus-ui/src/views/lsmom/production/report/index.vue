<template>
  <div class="p-2">
    <el-card shadow="never">
      <MobileReport :api="productionApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsProductionReport">
import { productionApi } from '@/api/lsmom/production';
import MobileReport from './MobileReport.vue';

/** 轻生产：移动填报（菜单 /production/report）。 */
</script>
