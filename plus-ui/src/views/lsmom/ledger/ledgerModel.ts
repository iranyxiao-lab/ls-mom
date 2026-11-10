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
