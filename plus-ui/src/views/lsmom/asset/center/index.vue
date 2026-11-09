<template>
  <div class="p-2">
    <el-card shadow="never">
      <AssetCenter :api="assetApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAssetCenter">
import { assetApi } from '@/api/lsmom/asset';
import AssetCenter from './AssetCenter.vue';

/** 设备：设备台账（菜单 /asset/center）。 */
</script>
