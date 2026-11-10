<template>
  <div class="p-2">
    <el-card shadow="never">
      <AppCenter :api="api" @navigate="(p) => router.push(p)" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsAppCenter">
import { useRouter } from 'vue-router';
import { packApi } from '@/api/lsmom/pack';
import AppCenter from './AppCenter.vue';
import { reloadPackMenu } from '@/layout/useGroupedMenus';

/** 协同应用中心页：/lsmom/apps。启用、停用、发布 Pack 后刷新导航菜单中的 Pack 入口。 */
const router = useRouter();
const after =
  <A extends unknown[], R>(fn: (...a: A) => Promise<R>) =>
  async (...a: A) => {
    const r = await fn(...a);
    void reloadPackMenu();
    return r;
  };
const api = { ...packApi, enable: after(packApi.enable), disable: after(packApi.disable), release: after(packApi.release) };
</script>
