<template>
  <div class="p-2">
    <el-card shadow="never">
      <el-tabs v-model="tab" data-testid="consultation-admin">
        <el-tab-pane v-for="t in TABS" :key="t.concept" :label="t.label" :name="t.concept" lazy>
          <LedgerWorkspace :concept="t.concept" :crud="lazyCrudApi" :forms="lazyFormApi" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsConsultationAdmin">
import { ref } from 'vue';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import LedgerWorkspace from '../../ledger/LedgerWorkspace.vue';

/** 议题管理（菜单 /consultation/admin）：新建议题（范围、截止时间、是否匿名）并发布；查看总结。意见只能在「参与征求」提交。 */
const TABS = [
  { label: '议题', concept: 'ls.consultation.Topic' },
  { label: '总结', concept: 'ls.consultation.Summary' }
];
const tab = ref(TABS[0].concept);
</script>
