import request from '@/utils/request';
import { flowApi } from './flow';
import { workbenchApi } from './workbench';
import type { FlowProgress, FlowProgressApi } from './flowProgress';

export const flowProgressHttp: FlowProgressApi = {
  progress: (concept, id) =>
    request({ url: `/api/workbench/biz/${encodeURIComponent(concept)}/${id}/progress`, method: 'get' }).then((r: any) => r.data as FlowProgress),
  context: (taskId) => workbenchApi.context(taskId),
  complete: (taskId, body) => flowApi.complete(taskId, body),
  returnTask: (taskId, target, reason) => flowApi.returnTask(taskId, target, reason),
  urge: (taskId) => flowApi.urge(taskId),
  transfer: (taskId, toParty, comment) => flowApi.transfer(taskId, toParty, comment),
  parties: (keyword) => workbenchApi.parties(keyword)
};
