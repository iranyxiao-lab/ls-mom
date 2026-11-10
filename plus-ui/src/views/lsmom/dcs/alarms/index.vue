<template>
  <div class="p-2">
    <el-card shadow="never">
      <AlarmBoard :api="dcsApi" :can-handle="checkPermi(['ls:dcs:handle'])" :can-confirm="checkPermi(['lsmom:issue:raise'])" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsDcsAlarms">
import { dcsApi } from '@/api/lsmom/dcs';
import { checkPermi } from '@/utils/permission';
import AlarmBoard from './AlarmBoard.vue';

/** DCS：报警处置（菜单 /dcs/alarms）。 */
</script>
