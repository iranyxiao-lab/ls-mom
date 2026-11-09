<template>
  <div class="p-2">
    <el-card shadow="never">
      <NcBoard :api="qualityApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsQualityNc">
import { qualityApi } from '@/api/lsmom/quality';
import NcBoard from './NcBoard.vue';

/** 质量：不符合项（菜单 /quality/nc）。 */
</script>
