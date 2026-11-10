import request from '@/utils/request';
import { productionApi } from '@/api/lsmom/production';
import { formApi } from '@/api/lsmom/form';
import type { OutboxItem, OutboxSender } from './outbox';

/** 离线队列的发送端：经 Operation Gateway 提交（带排队时生成的幂等键），照片上传后作为证据附件关联。 */
export const outboxSender: OutboxSender = {
  execute: async (item: OutboxItem) => {
    const r: any = await request({
      url: `/api/op/${item.operation}`,
      method: 'post',
      data: { targetId: item.targetId, version: item.version, input: item.input },
      headers: { 'Idempotency-Key': item.idempotencyKey, silentError: true, silentNetwork: true, repeatSubmit: false }
    });
    return r.data;
  },
  upload: (file: File) => formApi.upload(file) as Promise<{ ossId: string | number }>,
  attach: (ossId, objectType, objectId) => productionApi.attach(ossId, objectType, objectId, 'EVIDENCE'),
  explain: async (item, e) => {
    // 工作被取消后分派已释放，提交会被拒为无权限：查一下任务现在的状态，说清原因
    let st: string | undefined = e?.details?.currentStatus;
    if (!st && item.targetId && item.operation.startsWith('ls.core.Work.')) {
      try {
        const r: any = await request({
          url: '/api/production/my-work-status',
          method: 'get',
          params: { ids: item.targetId },
          headers: { silentError: true }
        });
        st = r.data?.[0]?.status;
      } catch {
        /* 查不到时用服务端原因 */
      }
    }
    if (st === 'CANCELLED') return '任务已取消';
    if (st && st !== 'IN_PROGRESS') return `任务状态已变为「${STATUS[st] ?? st}」`;
    return e?.message ?? '提交失败';
  }
};

const STATUS: Record<string, string> = { DONE: '已完成', CONFIRMED: '已确认', CANCELLED: '已取消', BLOCKED: '受阻', SUBMITTED: '已提交' };

/** 直接提交（在线时）：同样带幂等键，网络失败时调用方转为排队 */
export function submitOp(
  operation: string,
  targetId: string | undefined,
  version: number | undefined,
  input: Record<string, unknown>,
  idempotencyKey: string
) {
  return request({
    url: `/api/op/${operation}`,
    method: 'post',
    data: { targetId, version, input },
    headers: { 'Idempotency-Key': idempotencyKey, silentNetwork: true, repeatSubmit: false }
  }).then((r: any) => r.data as { targetId: string; version: number; status: string; warnings: string[]; data?: Record<string, any> });
}

export const newKey = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);
