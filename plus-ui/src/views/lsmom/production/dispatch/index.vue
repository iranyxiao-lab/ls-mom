<template>
  <div class="p-2">
    <el-card shadow="never">
      <DispatchDesk :api="productionApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsProductionDispatch">
import { productionApi } from '@/api/lsmom/production';
import DispatchDesk from './DispatchDesk.vue';

/** 轻生产：分派台（菜单 /production/dispatch）。 */
</script>
