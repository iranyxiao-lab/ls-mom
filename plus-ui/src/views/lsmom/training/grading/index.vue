<template>
  <div class="p-2">
    <el-card shadow="never">
      <GradingBoard :api="trainingApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsTrainingGrading">
import { trainingApi } from '@/api/lsmom/training';
import GradingBoard from './GradingBoard.vue';

/** 培训：阅卷（菜单 /training/grading）。 */
</script>
