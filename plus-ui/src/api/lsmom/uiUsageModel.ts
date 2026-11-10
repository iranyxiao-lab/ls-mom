/** 使用埋点报表的类型与显示名（不依赖请求层，页面与测试可直接引用）。 */
export interface UsageStep {
  step: string;
  abandoned: number;
  rate: number;
  high: boolean;
}
export interface UsageTask {
  task: string;
  started: number;
  completed: number;
  abandoned: number;
  errors: number;
  medianMs: number | null;
  steps: UsageStep[];
}

/** 埋点任务与步骤的显示名（未登记的按原名显示）。 */
export const TASK_LABELS: Record<string, string> = { 'nc.register': '登记不符合项' };
export const STEP_LABELS: Record<string, Record<string, string>> = {
  'nc.register': {
    title: '填写问题',
    description: '问题描述',
    org: '选择责任车间',
    severity: '选择严重程度',
    asset: '选择设备',
    'immediate-action': '填写立即措施',
    save: '提交'
  }
};
export const stepLabel = (task: string, step: string) => (step ? (STEP_LABELS[task]?.[step] ?? step) : '打开后未操作');
