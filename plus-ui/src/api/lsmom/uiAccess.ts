import request from '@/utils/request';

/** 错误页类型：找不到页面、没有权限、服务出错、网络断开。 */
export type ErrorKind = 'notFound' | 'forbidden' | 'server' | 'offline';

export interface AccessPage {
  path: string;
  title: string;
  trail: string;
  permission?: string | null;
}

/** 地址对应的系统页面（不存在时为 null）。 */
export const accessPage = (path: string): Promise<AccessPage | null> =>
  request({ url: '/api/ui/access/page', method: 'get', params: { path } }).then((r: any) => r.data ?? null);
/** 申请访问权限（通知管理员）。sent=false 表示 24 小时内已经申请过。 */
export const requestAccess = (path: string, reason?: string): Promise<{ sent: boolean }> =>
  request({ url: '/api/ui/access/request', method: 'post', data: { path, reason } }).then((r: any) => r.data);
