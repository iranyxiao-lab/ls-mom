<template>
  <div class="p-2">
    <el-card shadow="never">
      <QualityRecords :api="qualityApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsQualityRecords">
import { qualityApi } from '@/api/lsmom/quality';
import QualityRecords from './QualityRecords.vue';

/** 质量：质量记录（菜单 /quality/records）。 */
</script>
