import request from '@/utils/request';
import { crudApi } from './crud';

export interface NoticeAction {
  operation: string;
  concept: string;
  targetId: string;
  label: string;
}
export interface Notice {
  id: number;
  kind: string;
  title?: string | null;
  message: string;
  link?: string | null;
  action?: NoticeAction | null;
  createdAt: string;
  readAt?: string | null;
  doneAt?: string | null;
  fromName?: string | null;
}
export type NoticeGroup = 'todo' | 'system' | 'mention';
export type Notifications = Record<NoticeGroup, Notice[]> & { unread: Record<NoticeGroup, number> };

export const notificationApi = {
  list: (): Promise<Notifications> => request({ url: '/api/workbench/notifications', method: 'get' }).then((r: any) => r.data),
  read: (ids?: number[]) => request({ url: '/api/workbench/messages/read', method: 'post', data: { ids }, headers: { repeatSubmit: false } }),
  /** 抽屉内完成动作：按对象当前版本调用 Operation。 */
  async act(a: NoticeAction) {
    const rec = await crudApi.get(a.concept, Number(a.targetId));
    return crudApi.execute(a.operation, { targetId: String(a.targetId), version: Number(rec.version ?? 0), input: {} });
  }
};
