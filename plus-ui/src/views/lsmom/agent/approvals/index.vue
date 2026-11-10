<template>
  <div class="p-2">
    <el-card shadow="never">
      <AgentApprovals :api="approvalApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAgentApprovals">
import { approvalApi } from '@/api/lsmom/agent';
import AgentApprovals from './AgentApprovals.vue';

/** Agent 审批页：/agent/approvals。 */
</script>
