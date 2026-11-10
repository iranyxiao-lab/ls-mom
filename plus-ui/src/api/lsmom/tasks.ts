import request from '@/utils/request';

export type TaskKind = 'IMPORT_CHECK' | 'IMPORT' | 'EXPORT' | 'PACK_INSTALL' | 'BATCH' | string;
export type TaskStatus = 'RUNNING' | 'DONE' | 'FAILED';

/** 任务中心的一条任务（导入检查、导入、导出、Pack 安装来自服务端；批量操作在本页执行，local 为 true）。 */
export interface BgTask {
  id: number;
  kind: TaskKind;
  title: string;
  status: TaskStatus;
  total: number;
  done: number;
  failed: number;
  result?: Record<string, any> | null;
  error?: string | null;
  createdAt: string;
  finishedAt?: string | null;
  notified: boolean;
  local?: boolean;
}

const data = <T>(p: Promise<any>) => p.then((r: any) => r.data as T);

export const taskApi = {
  mine: (limit = 20) => data<BgTask[]>(request({ url: '/api/tasks', method: 'get', params: { limit }, headers: { silentError: true } })),
  get: (id: number) => data<BgTask>(request({ url: `/api/tasks/${id}`, method: 'get' })),
  notified: (id: number) => request({ url: `/api/tasks/${id}/notified`, method: 'post', headers: { repeatSubmit: false, silentError: true } })
};
