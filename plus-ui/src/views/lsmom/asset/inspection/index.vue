<template>
  <div class="p-2">
    <el-card shadow="never">
      <InspectionDesk :api="assetApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAssetInspection">
import { assetApi } from '@/api/lsmom/asset';
import InspectionDesk from './InspectionDesk.vue';

/** 设备：点检（菜单 /asset/inspection）。 */
</script>
