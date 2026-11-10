import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

export interface KnowledgeDoc {
  id: number;
  code: string;
  title: string;
  category: string;
  orgUnitId?: number;
  orgName?: string;
  classification: string;
  publishedVersion?: number;
  draftVersion?: number;
  chunks?: number;
  updatedAt?: string;
  versions?: { version: number; status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED'; changeNote?: string; content: string; createdAt: string; publishedAt?: string }[];
}

export interface Citation {
  n: number;
  documentId: number;
  documentCode: string;
  title: string;
  version: number;
  chunkNo: number;
  heading?: string;
  excerpt: string;
  score: number;
}

export interface Answer {
  type: 'METRIC' | 'DOCUMENT';
  question: string;
  answer: string;
  value?: number | string;
  unit?: string;
  metric?: { code: string; version: number; name: string };
  refs?: { type: string; id: string; label: string }[];
  drill?: { metric: string; version: number; from?: string; to?: string; total: number };
  citations: Citation[];
}

export interface KnowledgeApi {
  ask(question: string): Promise<Answer>;
  list(): Promise<KnowledgeDoc[]>;
  get(id: number): Promise<KnowledgeDoc>;
  create(body: { code: string; title: string; category?: string; orgUnitId?: number; classification?: string; content: string }): Promise<KnowledgeDoc>;
  saveDraft(id: number, content: string, note?: string): Promise<KnowledgeDoc>;
  publish(id: number): Promise<KnowledgeDoc>;
}

export const knowledgeApi: KnowledgeApi = {
  ask: (question) => data(request({ url: '/api/knowledge/ask', method: 'post', data: { question }, headers: { repeatSubmit: false } })),
  list: () => data(request({ url: '/api/knowledge/documents', method: 'get' })),
  get: (id) => data(request({ url: `/api/knowledge/documents/${id}`, method: 'get' })),
  create: (body) => data(request({ url: '/api/knowledge/documents', method: 'post', data: body })),
  saveDraft: (id, content, note) => data(request({ url: `/api/knowledge/documents/${id}/draft`, method: 'put', data: { content, note } })),
  publish: (id) => data(request({ url: `/api/knowledge/documents/${id}/publish`, method: 'post' }))
};
