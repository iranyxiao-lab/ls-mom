<template>
  <div class="p-2">
    <el-card shadow="never">
      <AgentAdmin :api="agentApi" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAgentAdmin">
import { agentApi } from '@/api/lsmom/agent';
import AgentAdmin from './AgentAdmin.vue';

/** Agent 管理页：/agent/admin。 */
</script>
