<template>
  <div class="p-2">
    <el-card shadow="never">
      <WorkCenter :api="workbenchApi" :actions="actions" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsWorkbench">
import { flowApi } from '@/api/lsmom/flow';
import { workbenchApi } from '@/api/lsmom/workbench';
import WorkCenter from './WorkCenter.vue';

/** 协同工作中心页：/lsmom/workbench。 */
const actions = {
  complete: flowApi.complete,
  returnTask: flowApi.returnTask,
  transfer: flowApi.transfer,
  urge: flowApi.urge,
  readCc: flowApi.readCc,
  parties: workbenchApi.parties
};
</script>
