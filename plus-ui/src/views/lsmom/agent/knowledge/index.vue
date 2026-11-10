<template>
  <div class="p-2">
    <el-card shadow="never">
      <KnowledgeCenter :api="knowledgeApi" :can-manage="checkPermi(['lsmom:knowledge:manage'])" :can-publish="checkPermi(['lsmom:knowledge:publish'])" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsKnowledge">
import { knowledgeApi } from '@/api/lsmom/knowledge';
import { checkPermi } from '@/utils/permission';
import KnowledgeCenter from './KnowledgeCenter.vue';

/** 知识问答与知识库维护：/agent/knowledge。 */
</script>
