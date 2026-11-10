<template>
  <LsSkeleton v-if="loading" variant="card" :rows="3" />
  <ErrorPage v-else :kind="page ? 'forbidden' : 'notFound'" :page="page" :path="path" :trace-id="trace" />
</template>

<script setup lang="ts">
/**
 * 路由未匹配时：先查该地址是不是系统里的页面——是则说明当前用户缺少的权限（403，可申请），否则显示找不到页面（404）。
 * 用户能访问的页面都已在路由表里，所以命中这里的系统页面一定是没有权限。
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { LsSkeleton } from '@/components/LsState';
import ErrorPage from './ErrorPage.vue';
import { accessPage, type AccessPage } from '@/api/lsmom/uiAccess';
import { lastTraceId } from '@/utils/trace';

const route = useRoute();
const path = computed(() => route.fullPath);
const page = ref<AccessPage | null>(null);
const loading = ref(true);
const trace = ref('');

onMounted(async () => {
  try {
    page.value = await accessPage(route.path);
  } catch {
    page.value = null;
  } finally {
    trace.value = lastTraceId.value;
    loading.value = false;
  }
});
</script>
