/** 摘要类型与引用类型的显示名。 */
export const KIND_LABEL: Record<string, string> = { ISSUE: '异常', SHIFT_HANDOVER: '交接班', DCS_ALARM: 'DCS 报警', CONSULTATION: '咨询' };
export const REF_LABEL: Record<string, string> = {
  'ls.core.Issue': '异常',
  'ls.core.Work': '工作',
  'ls.core.Event': '事件',
  'ls.dcs.Alarm': '报警',
  'ls.dcs.AlarmEvent': '报警事件',
  'ls.dcs.Snapshot': '快照',
  'ls.production.ShiftHandover': '交接班',
  'ls.production.ShiftHandoverItem': '交接事项'
};
