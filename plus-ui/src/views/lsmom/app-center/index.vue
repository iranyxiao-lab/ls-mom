<template>
  <div class="p-2">
    <el-card shadow="never">
      <AppCenter :api="packApi" @navigate="(p) => router.push(p)" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAppCenter">
import { useRouter } from 'vue-router';
import { packApi } from '@/api/lsmom/pack';
import AppCenter from './AppCenter.vue';

/** 协同应用中心页：/lsmom/apps。 */
const router = useRouter();
</script>
