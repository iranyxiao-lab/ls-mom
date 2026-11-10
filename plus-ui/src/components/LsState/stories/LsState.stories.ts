import type { Meta, StoryObj } from '@storybook/vue3-vite';
import { LsBadge, LsEmpty, LsError, LsSkeleton, LsStatusTag, STATUS, lsSuccess } from '../index';

const meta: Meta = {
  title: '设计系统/状态与反馈',
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          '统一的空态、骨架屏、错误态、状态标签（颜色＋图标＋文字）、徽标与成功提示。业务页面复用这些组件，不各写一套；系统开启"减少动态效果"时骨架屏不闪烁、抽屉与页签不做位移动画。'
      }
    }
  }
};
export default meta;

export const StatusTags: StoryObj = {
  render: () => ({
    components: { LsStatusTag },
    setup: () => ({ codes: Object.keys(STATUS) }),
    template: `<div style="display:flex;flex-wrap:wrap;gap:var(--ls-space-2)"><LsStatusTag v-for="c in codes" :key="c" :status="c" /></div>`
  })
};

export const Empty: StoryObj = {
  render: () => ({
    components: { LsEmpty },
    template: `<LsEmpty title="暂无待办" description="你的待办都处理完了。发起的单据可以在「我发起的」里查看进度。" action-text="查看我发起的" />`
  })
};

export const Skeletons: StoryObj = {
  render: () => ({
    components: { LsSkeleton },
    template: `<div style="display:grid;gap:var(--ls-space-6)"><LsSkeleton variant="table" :rows="4" /><LsSkeleton variant="form" :rows="3" /><LsSkeleton variant="card" :rows="3" /></div>`
  })
};

export const ErrorState: StoryObj = {
  render: () => ({
    components: { LsError },
    template: `<LsError title="加载失败" reason="网络连接中断，最近的修改已保存为草稿。" next-step="请检查网络后重试；问题持续时把追踪编号发给管理员。" trace-id="9f3c2a7e1b4d4c55" retryable />`
  })
};

export const BadgesAndToast: StoryObj = {
  render: () => ({
    components: { LsBadge },
    setup: () => ({ toast: () => lsSuccess('已提交审核', { undo: () => lsSuccess('已撤销') }) }),
    template: `<div style="display:flex;gap:var(--ls-space-8);align-items:center">
      <LsBadge :count="3"><el-button aria-label="通知"><span class="i-lucide-bell" aria-hidden="true" /></el-button></LsBadge>
      <LsBadge :count="120" tone="primary"><el-button>待办</el-button></LsBadge>
      <el-button type="primary" data-testid="toast-trigger" @click="toast">提交（成功提示带撤销）</el-button>
    </div>`
  })
};
