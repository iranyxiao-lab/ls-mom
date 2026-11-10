/**
 * 最近一次请求的追踪编号（trace_id，后端响应头 X-Trace-Id）：错误提示与错误页显示，方便反馈给管理员排查。
 * 纯状态模块，不依赖请求模块。
 */
import { ref } from 'vue';

export const TRACE_HEADER = 'x-trace-id';
export const lastTraceId = ref<string>('');

export function rememberTrace(headers: Record<string, any> | undefined): string {
  const t = headers?.[TRACE_HEADER] ?? headers?.get?.(TRACE_HEADER);
  if (t) lastTraceId.value = String(t);
  return t ? String(t) : '';
}

/** 错误提示后面附上追踪编号。 */
export const withTrace = (msg: string, trace?: string) => (trace ? `${msg}（追踪编号 ${trace}）` : msg);
