<template>
  <div class="p-2">
    <el-card shadow="never">
      <MyExams :api="trainingApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsTrainingExams">
import { trainingApi } from '@/api/lsmom/training';
import MyExams from './MyExams.vue';

/** 培训：我的考试（菜单 /training/exams）。 */
</script>
