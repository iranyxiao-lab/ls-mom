/** 质量 Pack 的常量与类型（不引用请求模块，组件可直接导入运行时值）。 */
export const QUALITY_RECORD = 'ls.quality.QualityRecord';
export const NC = 'ls.quality.NonConformance';

export const RECORD_TYPES: Record<string, string> = { INSPECTION: '检验', SAMPLING: '抽检', PATROL: '巡检', COMPLAINT: '客诉', OTHER: '其他' };
export const RESULTS: Record<string, string> = { PASS: '合格', FAIL: '不合格', CONCESSION: '让步接收', NA: '不适用' };
export const LINK_TYPES: Record<string, string> = { WORK: '工作', ASSET: '设备', OUTPUT: '产出记录', MATERIAL: '物料记录', EXTERNAL: '外部对象' };
export const SEVERITIES: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高' };

export const NC_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_AUDIT: '待车间审核',
  IN_RECTIFICATION: '整改中',
  PENDING_VERIFY: '待验证',
  CLOSED: '已关闭',
  REJECTED: '已驳回'
};
export const NC_STATUS_TYPE: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
  DRAFT: 'info',
  PENDING_AUDIT: 'danger',
  IN_RECTIFICATION: 'warning',
  PENDING_VERIFY: 'primary',
  CLOSED: 'success',
  REJECTED: 'info'
};

export interface LinkRow {
  objectType: string;
  objectId: string;
  label?: string;
}

export interface ObjectOption {
  id: number;
  label: string;
}

/** 不符合项条件必填（与 Pack 规则、表单规则一致）：严重程度为高时相关设备与立即措施必填。 */
export function ncMissing(nc: { severity?: string; asset?: unknown; immediateAction?: string }): string[] {
  if (nc.severity !== 'HIGH') return [];
  const out: string[] = [];
  if (nc.asset == null || nc.asset === '') out.push('asset');
  if (!nc.immediateAction || !String(nc.immediateAction).trim()) out.push('immediateAction');
  return out;
}
