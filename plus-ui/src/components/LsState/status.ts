/** 状态 → 色调 + 图标 + 文字。状态不能只靠颜色区分（WCAG 1.4.1），三者同时出现。 */
export type Tone = 'neutral' | 'info' | 'primary' | 'success' | 'warning' | 'danger';

export interface StatusStyle {
  tone: Tone;
  icon: string;
  label: string;
}

export const TONE_ICON: Record<Tone, string> = {
  neutral: 'i-lucide-circle-dashed',
  info: 'i-lucide-clock',
  primary: 'i-lucide-circle-play',
  success: 'i-lucide-circle-check',
  warning: 'i-lucide-triangle-alert',
  danger: 'i-lucide-circle-x'
};

/** 平台通用状态（工作、异常、单据、流程）。业务可通过 labels / tones 覆盖。 */
export const STATUS: Record<string, StatusStyle> = {
  DRAFT: { tone: 'neutral', icon: 'i-lucide-pencil-line', label: '草稿' },
  READY: { tone: 'info', icon: 'i-lucide-circle-dot', label: '待开始' },
  ASSIGNED: { tone: 'info', icon: 'i-lucide-user-check', label: '已分派' },
  SUBMITTED: { tone: 'info', icon: 'i-lucide-send', label: '已提交' },
  PENDING: { tone: 'info', icon: 'i-lucide-clock', label: '待处理' },
  PENDING_CONFIRM: { tone: 'info', icon: 'i-lucide-clock', label: '待确认' },
  REVIEWING: { tone: 'info', icon: 'i-lucide-clock', label: '审核中' },
  IN_PROGRESS: { tone: 'primary', icon: 'i-lucide-circle-play', label: '进行中' },
  RECTIFYING: { tone: 'primary', icon: 'i-lucide-wrench', label: '整改中' },
  VERIFYING: { tone: 'primary', icon: 'i-lucide-search-check', label: '验证中' },
  DONE: { tone: 'success', icon: 'i-lucide-circle-check', label: '已完成' },
  CLOSED: { tone: 'success', icon: 'i-lucide-circle-check', label: '已关闭' },
  ARCHIVED: { tone: 'success', icon: 'i-lucide-archive', label: '已归档' },
  PUBLISHED: { tone: 'success', icon: 'i-lucide-circle-check', label: '已发布' },
  APPROVED: { tone: 'success', icon: 'i-lucide-circle-check', label: '已通过' },
  ENABLED: { tone: 'success', icon: 'i-lucide-circle-check', label: '已启用' },
  BLOCKED: { tone: 'warning', icon: 'i-lucide-octagon-pause', label: '受阻' },
  OVERDUE: { tone: 'warning', icon: 'i-lucide-alarm-clock', label: '已逾期' },
  RETURNED: { tone: 'warning', icon: 'i-lucide-undo-2', label: '已退回' },
  REJECTED: { tone: 'danger', icon: 'i-lucide-circle-x', label: '已驳回' },
  FAILED: { tone: 'danger', icon: 'i-lucide-circle-x', label: '失败' },
  CANCELLED: { tone: 'neutral', icon: 'i-lucide-ban', label: '已取消' },
  DISCARDED: { tone: 'neutral', icon: 'i-lucide-ban', label: '已作废' },
  DISABLED: { tone: 'neutral', icon: 'i-lucide-ban', label: '已停用' }
};

export function statusStyle(code: string, overrides?: { label?: string; tone?: Tone; icon?: string }): StatusStyle {
  const base = STATUS[code] ?? { tone: 'neutral' as Tone, icon: TONE_ICON.neutral, label: code };
  const tone = overrides?.tone ?? base.tone;
  return { tone, icon: overrides?.icon ?? (overrides?.tone ? TONE_ICON[tone] : base.icon), label: overrides?.label ?? base.label };
}
