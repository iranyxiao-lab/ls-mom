<template>
  <div class="p-2">
    <el-card shadow="never">
      <RequestBoard :api="productionApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsProductionRequests">
import { productionApi } from '@/api/lsmom/production';
import RequestBoard from './RequestBoard.vue';

/** 轻生产：生产需求板（菜单 /production/requests）。 */
</script>
