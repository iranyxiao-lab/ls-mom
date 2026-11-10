<template>
  <ErrorPage :kind="kind" :trace-id="trace" />
</template>

<script setup lang="ts">
/** /500 与 /offline：服务出错、网络断开（由路由加载失败等处跳转过来，?from= 为原地址，?trace= 为追踪编号）。 */
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ErrorPage from './ErrorPage.vue';
import type { ErrorKind } from '@/api/lsmom/uiAccess';
import { lastTraceId } from '@/utils/trace';

const route = useRoute();
const kind = computed<ErrorKind>(() => (route.path.startsWith('/offline') ? 'offline' : 'server'));
const trace = computed(() => (route.query.trace as string) || (kind.value === 'server' ? lastTraceId.value : ''));
</script>
