<template>
  <div class="p-2">
    <el-card shadow="never">
      <MyTopics :api="consultationApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsConsultationTopics">
import { consultationApi } from '@/api/lsmom/consultation';
import MyTopics from './MyTopics.vue';

/** 意见咨询：参与征求（菜单 /consultation/topics）。 */
</script>
