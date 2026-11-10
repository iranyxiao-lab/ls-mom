<template>
  <main class="ls-error-page" :data-kind="kind" data-testid="error-page">
    <p class="code" aria-hidden="true">{{ CODE[kind] }}</p>
    <h1 class="title" data-testid="error-title">{{ text.title }}</h1>
    <p class="reason" data-testid="error-reason">{{ text.reason }}</p>
    <dl v-if="kind === 'forbidden' && page" class="detail">
      <dt>页面</dt>
      <dd data-testid="error-page-trail">{{ page.trail }}</dd>
      <template v-if="page.permission">
        <dt>缺少权限</dt>
        <dd>
          <code data-testid="error-permission">{{ page.permission }}</code>
        </dd>
      </template>
    </dl>
    <p class="next">{{ text.next }}</p>
    <div class="actions">
      <template v-if="kind === 'forbidden' && page">
        <el-input
          v-model="reason"
          class="reason-input"
          maxlength="200"
          placeholder="申请理由（选填）"
          aria-label="申请理由"
          data-testid="access-reason"
        />
        <el-button type="primary" :loading="sending" :disabled="sent" data-testid="btn-request-access" @click="apply">
          {{ sent ? '已提交申请' : '申请权限' }}
        </el-button>
      </template>
      <el-button v-if="kind === 'server' || kind === 'offline'" type="primary" data-testid="btn-error-retry" @click="retry">重试</el-button>
      <el-button v-if="canBack" data-testid="btn-error-back" @click="router.back()">返回上一页</el-button>
      <el-button data-testid="btn-error-home" @click="router.push('/index')">返回首页</el-button>
    </div>
    <p v-if="traceId" class="trace">
      追踪编号 <code data-testid="error-trace">{{ traceId }}</code>
      <el-button link size="small" aria-label="复制追踪编号" @click="copy">复制</el-button>
    </p>
  </main>
</template>

<script setup lang="ts">
/**
 * 统一的错误页：找不到页面（404）、没有权限（403，说明缺少的权限并可申请）、服务出错（500）、网络断开。
 * 每种都说明原因与下一步，并显示追踪编号（trace_id），方便反馈给管理员。
 */
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { requestAccess, type AccessPage, type ErrorKind } from '@/api/lsmom/uiAccess';

const props = defineProps<{ kind: ErrorKind; page?: AccessPage | null; path?: string; traceId?: string }>();
const router = useRouter();
const canBack = typeof history !== 'undefined' && history.length > 1;
const reason = ref('');
const sending = ref(false);
const sent = ref(false);

const CODE: Record<ErrorKind, string> = { notFound: '404', forbidden: '403', server: '500', offline: '离线' };
const text = computed(() => {
  switch (props.kind) {
    case 'forbidden':
      return {
        title: '没有访问权限',
        reason: `你的账号没有打开「${props.page?.title ?? '该页面'}」的权限。`,
        next: '可以直接申请权限（会通知系统管理员），或联系你的主管为你开通。'
      };
    case 'server':
      return {
        title: '服务出错了',
        reason: '页面加载或数据处理时服务端出现异常，你的操作可能没有完成。',
        next: '请稍后重试；问题持续出现时，把追踪编号发给系统管理员。'
      };
    case 'offline':
      return {
        title: '网络已断开',
        reason: '当前设备没有连接到网络，页面无法加载。',
        next: '检查网络连接（Wi-Fi 或移动数据），恢复后点「重试」。'
      };
    default:
      return {
        title: '找不到这个页面',
        reason: `地址 ${props.path ?? ''} 不存在，可能已被移动或删除，也可能是链接写错了。`,
        next: '从菜单或命令面板（Ctrl/⌘+K）搜索要找的功能。'
      };
  }
});

async function apply() {
  if (!props.page) return;
  sending.value = true;
  try {
    const r = await requestAccess(props.page.path, reason.value);
    sent.value = true;
    ElMessage.success(r.sent ? '已提交申请，管理员处理后重新登录即可访问' : '你在 24 小时内已经申请过，管理员会尽快处理');
  } finally {
    sending.value = false;
  }
}

function retry() {
  const from = (router.currentRoute.value.query.from as string) || '/index';
  // 整页重新加载：断网或资源加载失败后，旧的页面资源可能已失效
  location.assign(from);
}

async function copy() {
  try {
    await navigator.clipboard.writeText(props.traceId ?? '');
    ElMessage.success('已复制追踪编号');
  } catch {
    ElMessage.warning('无法访问剪贴板，请手动复制');
  }
}
</script>

<style scoped lang="scss">
.ls-error-page {
  max-width: 640px;
  margin: 10vh auto;
  padding: 0 var(--ls-space-4);
  color: var(--el-text-color-primary);
}
.code {
  margin: 0;
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  color: var(--el-color-primary);
}
[data-kind='forbidden'] .code,
[data-kind='server'] .code {
  color: var(--el-color-danger);
}
[data-kind='offline'] .code {
  color: var(--el-color-warning);
}
.title {
  margin: var(--ls-space-4) 0 var(--ls-space-2);
  font-size: var(--ls-font-size-xl, 22px);
}
.reason,
.next {
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
.detail {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--ls-space-1) var(--ls-space-3);
  padding: var(--ls-space-3);
  border-radius: var(--ls-radius-md, 6px);
  background: var(--el-fill-color-light);
  dt {
    color: var(--el-text-color-secondary);
  }
  dd {
    margin: 0;
  }
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ls-space-2);
  margin-top: var(--ls-space-4);
}
.reason-input {
  flex: 1 1 240px;
}
.trace {
  margin-top: var(--ls-space-6);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
