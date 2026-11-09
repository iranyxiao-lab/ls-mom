<template>
  <div class="p-2">
    <el-card shadow="never">
      <WorkBoard :api="productionApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsProductionBoard">
import { productionApi } from '@/api/lsmom/production';
import WorkBoard from './WorkBoard.vue';

/** 轻生产：工作板（菜单 /production/board）。 */
</script>
