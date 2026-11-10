import request from '@/utils/request';
import { crudApi } from './crud';
import type { OpResult } from './productionModel';
import type { AnswerItem, MyExam } from './trainingModel';

export * from './trainingModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));
const op = (code: string, targetId: string | number | undefined, version: number | undefined, input: Record<string, unknown> = {}) =>
  crudApi.execute(code, { targetId: targetId === undefined ? undefined : String(targetId), version, input }, key()) as Promise<OpResult>;

export interface AnswerSheet extends Record<string, any> {
  id: number;
  version: number;
  status: string;
  examTitle: string;
  candidateName: string;
  deadline: string;
  /** 作答中：服务器按自身时钟算出的剩余秒数。 */
  remainingSeconds?: number;
  items: AnswerItem[];
}

export const trainingApi = {
  myExams: () => data<MyExam[]>(request({ url: '/api/training/my/exams', method: 'get' })),
  answer: (id: number | string) => data<AnswerSheet>(request({ url: `/api/training/answers/${id}`, method: 'get' })),
  grading: () => data<Record<string, any>[]>(request({ url: '/api/training/grading', method: 'get' })),
  myRecords: () => data<Record<string, any>[]>(request({ url: '/api/training/my/records', method: 'get' })),
  start: (examId: number | string) => op('ls.training.Exam.start', undefined, undefined, { examId: Number(examId) }),
  save: (id: number | string, version: number, responses: Record<string, string>) => op('ls.training.Answer.save', id, version, { responses }),
  submit: (id: number | string, version: number, responses: Record<string, string>) => op('ls.training.Answer.submit', id, version, { responses }),
  gradeEssay: (id: number | string, version: number, scores: Record<string, number>) => op('ls.training.Answer.gradeEssay', id, version, { scores })
};

export type TrainingApi = typeof trainingApi;
