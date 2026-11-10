import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

export interface SummaryRef {
  type: string;
  id: string;
  label?: string;
}

export interface SummaryRecord {
  id: number;
  kind: string;
  subjectConcept: string;
  subjectId: string;
  title: string;
  content: string;
  points: { text: string; refs: SummaryRef[] }[];
  citations: SummaryRef[];
  status: 'DRAFT' | 'PUBLISHED' | 'DISCARDED';
  orgUnitId?: number;
  source: 'HUMAN' | 'AGENT';
  model?: string;
  createdByParty?: number;
  onBehalfOf?: number;
  publishedBy?: number;
  publishedAt?: string;
  discardReason?: string;
  createdAt: string;
  version: number;
}

export interface SummaryApi {
  list(q: { status?: string; kind?: string }): Promise<SummaryRecord[]>;
  publish(s: SummaryRecord): Promise<unknown>;
  discard(s: SummaryRecord, reason: string): Promise<unknown>;
}

const op = (code: string, s: SummaryRecord, input: Record<string, unknown>) =>
  data(request({ url: `/api/op/${code}`, method: 'post', data: { targetId: String(s.id), version: s.version, input } }));

export const summaryApi: SummaryApi = {
  list: (q) => data(request({ url: '/api/summaries', method: 'get', params: q })),
  publish: (s) => op('ls.core.Summary.publish', s, {}),
  discard: (s, reason) => op('ls.core.Summary.discard', s, { reason })
};
