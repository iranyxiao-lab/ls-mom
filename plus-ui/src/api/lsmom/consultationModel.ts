/** 意见咨询：前端模型与纯函数。 */
export const CATEGORIES: Record<string, string> = {
  SAFETY: '安全',
  QUALITY: '质量',
  PROCESS: '工艺',
  EQUIPMENT: '设备',
  WELFARE: '后勤福利',
  OTHER: '其他'
};

export const OPINION_STATUS: Record<string, string> = { SUBMITTED: '待答复', REPLIED: '已答复', CONVERTED: '已转整改' };
export const OPINION_STATUS_TYPE: Record<string, any> = { SUBMITTED: 'warning', REPLIED: 'success', CONVERTED: 'danger' };
export const TOPIC_STATUS: Record<string, string> = { DRAFT: '草稿', OPEN: '征求中', CLOSED: '已截止' };

export interface Topic {
  id: number;
  version: number;
  status: string;
  title: string;
  description?: string;
  scope_name?: string;
  deadline: string;
  anonymous: boolean;
  owner_name?: string;
  summary_id?: number | null;
  my_count?: number;
  total?: number;
  pending?: number;
  converted?: number;
}

export interface Opinion {
  id: number;
  version: number;
  status: string;
  topic_id: number;
  topic_title: string;
  anonymous: boolean;
  content: string;
  category?: string | null;
  author_name?: string | null;
  submitter_name?: string | null;
  submitted_at: string;
  reply?: string | null;
  issue_id?: number | null;
  issue_code?: string | null;
  issue_status?: string | null;
}

export interface SummaryPointView {
  seq: number;
  viewpoint: string;
  opinions: { id: number; content: string; category?: string }[];
}

export interface DraftPoint {
  viewpoint: string;
  opinionIds: number[];
}

/** 署名显示：匿名议题不显示提交人。 */
export const authorLabel = (o: Opinion) => (o.anonymous && !o.submitter_name ? '匿名' : (o.author_name ?? o.submitter_name ?? '—'));

/** 总结草稿校验：总结必填；每个观点必填且至少引用一条意见。 */
export function summaryErrors(content: string, points: DraftPoint[]): string[] {
  const out: string[] = [];
  if (!content.trim()) out.push('请填写总结');
  if (!points.length) out.push('至少要有一个观点');
  points.forEach((p, i) => {
    if (!p.viewpoint.trim()) out.push(`第 ${i + 1} 个观点未填写`);
    if (!p.opinionIds.length) out.push(`第 ${i + 1} 个观点没有引用来源意见`);
  });
  return out;
}

export const fmtTime = (v?: string | null) => (v ? String(v).replace('T', ' ').slice(0, 16) : '');
