import request from '@/utils/request';
import type { UsageTask } from './uiUsageModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

export const uiUsageApi = {
  config: () => data<{ enabled: boolean }>(request({ url: '/api/ui/usage/config', method: 'get' })),
  setEnabled: (enabled: boolean) => data<{ enabled: boolean }>(request({ url: '/api/ui/usage/config', method: 'put', data: { enabled } })),
  report: (days: number) => data<UsageTask[]>(request({ url: '/api/ui/usage/report', method: 'get', params: { days } }))
};
export type UiUsageApi = typeof uiUsageApi;
