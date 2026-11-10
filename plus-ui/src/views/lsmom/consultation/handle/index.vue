<template>
  <div class="p-2">
    <el-card shadow="never">
      <HandleBoard :api="consultationApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsConsultationHandle">
import { consultationApi } from '@/api/lsmom/consultation';
import HandleBoard from './HandleBoard.vue';

/** 意见咨询：意见处理（菜单 /consultation/handle）。 */
</script>
