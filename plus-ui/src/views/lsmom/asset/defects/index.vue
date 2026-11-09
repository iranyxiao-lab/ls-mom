<template>
  <div class="p-2">
    <el-card shadow="never">
      <DefectBoard :api="assetApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAssetDefects">
import { assetApi } from '@/api/lsmom/asset';
import DefectBoard from './DefectBoard.vue';

/** 设备：缺陷与维修（菜单 /asset/defects）。 */
</script>
