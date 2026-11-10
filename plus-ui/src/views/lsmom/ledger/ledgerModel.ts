/** 通用台账工作区的纯函数与常量（组件从这里取运行时值）。 */
export const EXPORT_STATUS: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '导出中',
  DONE: '已完成',
  FAILED: '失败',
  REJECTED: '被拒绝'
};

export function idempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 在页面内保存文件（不打开新窗口）。 */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 记录的显示名：标题/名称类字段优先，否则取列表里第一个文本字段。 */
export function recordLabel(r: Record<string, any>, schema: { fields: { code: string; type: string }[] } | null): string {
  for (const k of ['title', 'name', 'code', 'no']) if (r[k]) return String(r[k]);
  const f = schema?.fields.find((x) => x.type === 'STRING' && r[x.code]);
  return f ? String(r[f.code]) : `#${r.id}`;
}
