<template>
  <div class="p-2">
    <el-card shadow="never">
      <el-tabs v-model="tab" data-testid="training-admin">
        <el-tab-pane v-for="t in TABS" :key="t.concept" :label="t.label" :name="t.concept" lazy>
          <LedgerWorkspace :concept="t.concept" :crud="lazyCrudApi" :forms="lazyFormApi" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsTrainingAdmin">
import { ref } from 'vue';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import LedgerWorkspace from '../../ledger/LedgerWorkspace.vue';

/** 培训管理（菜单 /training/admin）：课程（提交审批后发布）、题库（人工复核后可用，含 LLM 草稿）、考试（组卷、审批后发布）、培训记录。 */
const TABS = [
  { label: '课程', concept: 'ls.training.Course' },
  { label: '题库', concept: 'ls.training.Question' },
  { label: '考试', concept: 'ls.training.Exam' },
  { label: '培训记录', concept: 'ls.training.TrainingRecord' }
];
const tab = ref(TABS[0].concept);
</script>
