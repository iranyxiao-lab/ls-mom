import request from '@/utils/request';
import { crudApi } from './crud';
import type { OpResult } from './productionModel';
import type { DraftPoint, Opinion, SummaryPointView, Topic } from './consultationModel';

export * from './consultationModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));
const op = (code: string, targetId: string | number | undefined, version: number | undefined, input: Record<string, unknown> = {}) =>
  crudApi.execute(code, { targetId: targetId === undefined ? undefined : String(targetId), version, input }, key()) as Promise<OpResult>;

export interface SummaryView extends Record<string, any> {
  id: number;
  version: number;
  status: string;
  content: string;
  origin: string;
  confirmedByName?: string | null;
  publishedAt?: string | null;
  points: SummaryPointView[];
}

export const consultationApi = {
  myTopics: () => data<Topic[]>(request({ url: '/api/consultation/my/topics', method: 'get' })),
  myOpinions: (topicId?: number) => data<Opinion[]>(request({ url: '/api/consultation/my/opinions', method: 'get', params: { topicId } })),
  topics: () => data<Topic[]>(request({ url: '/api/consultation/topics', method: 'get' })),
  opinions: (topicId: number) => data<Opinion[]>(request({ url: `/api/consultation/topics/${topicId}/opinions`, method: 'get' })),
  summary: (topicId: number) => data<SummaryView | null>(request({ url: `/api/consultation/topics/${topicId}/summary`, method: 'get' })),
  submit: (topicId: number, content: string, category?: string) =>
    op('ls.consultation.Opinion.submit', undefined, undefined, { topicId: Number(topicId), content, ...(category ? { category } : {}) }),
  reply: (o: { id: number; version: number }, reply: string, category?: string) =>
    op('ls.consultation.Opinion.reply', o.id, o.version, { reply, ...(category ? { category } : {}) }),
  toIssue: (o: { id: number; version: number }, input: { title?: string; severity?: string; reply?: string }) =>
    op('ls.consultation.Opinion.toIssue', o.id, o.version, input),
  closeTopic: (t: { id: number; version: number }) => op('ls.consultation.Topic.close', t.id, t.version),
  draftSummary: (topicId: number, content: string, points: DraftPoint[]) =>
    op('ls.consultation.Summary.draft', undefined, undefined, { topicId: Number(topicId), content, origin: 'MANUAL', points }),
  publishSummary: (s: { id: number; version: number }) => op('ls.consultation.Summary.publish', s.id, s.version),
  discardSummary: (s: { id: number; version: number }) => op('ls.consultation.Summary.discard', s.id, s.version)
};

export type ConsultationApi = typeof consultationApi;
