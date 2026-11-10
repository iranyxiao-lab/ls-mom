import request from '@/utils/request';
import type { AnyViewSchema, FormSchema, RefSpec, ViewIssue, ViewVersion } from '@/types/lsView';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const enc = encodeURIComponent;

export interface RefOption {
  id: number;
  label: string;
}

export interface CheckResult {
  type: string;
  issues: ViewIssue[];
  fieldRefs: { conceptCode: string; fieldCode: string; detail?: string }[];
}

export interface PublishResult {
  changeSetId: number;
  status: 'APPLIED' | 'REVIEWING';
  breaking: boolean;
  removedFields: string[];
  addedFields: string[];
}

export interface FormContextData {
  partyId?: number | null;
  orgId?: number | null;
  shift?: string | null;
  now?: string;
  remembered?: Record<string, unknown>;
}

export const formApi = {
  list: (params: { concept?: string; type?: string } = {}) => data<any[]>(request({ url: '/api/form/views', method: 'get', params })),
  get: <S = AnyViewSchema>(code: string, version?: number) =>
    data<ViewVersion<S>>(request({ url: `/api/form/views/${enc(code)}`, method: 'get', params: version ? { version } : {} })),
  defaultFor: (concept: string, type = 'FORM') =>
    data<ViewVersion<FormSchema> | null>(request({ url: `/api/form/concept/${enc(concept)}/default`, method: 'get', params: { type } })),
  history: (code: string) => data<any[]>(request({ url: `/api/form/views/${enc(code)}/history`, method: 'get' })),
  editVersion: (code: string, recordVersion: number) =>
    data<{ recordVersion: number; latestVersion: number; strategy: string; outdated: boolean; useVersion: number; prompt: boolean; note?: string }>(
      request({ url: `/api/form/views/${enc(code)}/edit-version`, method: 'get', params: { recordVersion } })
    ),
  refSearch: (body: { ref: RefSpec; keyword?: string; ids?: number[]; orgId?: number; model?: Record<string, unknown> }) =>
    data<RefOption[]>(request({ url: '/api/form/ref/search', method: 'post', data: body })),
  components: () => data<any[]>(request({ url: '/api/form/components', method: 'get' })),
  saveDraft: (schema: AnyViewSchema, note?: string) =>
    data<CheckResult>(request({ url: '/api/form/drafts', method: 'post', data: { schema, note } })),
  validate: (schema: AnyViewSchema) => data<CheckResult>(request({ url: '/api/form/validate', method: 'post', data: schema })),
  derive: (code: string) => data<ViewVersion>(request({ url: `/api/form/views/${enc(code)}/derive`, method: 'post' })),
  check: (code: string, version: number) => data<CheckResult>(request({ url: `/api/form/views/${enc(code)}/${version}/check`, method: 'get' })),
  publish: (code: string, version: number) =>
    data<PublishResult>(request({ url: `/api/form/views/${enc(code)}/${version}/publish`, method: 'post' })),
  review: (id: number, approve: boolean) => data<void>(request({ url: `/api/form/changesets/${id}/review`, method: 'post', data: { approve } })),
  apply: (id: number) => data<unknown>(request({ url: `/api/form/changesets/${id}/apply`, method: 'post' })),
  dict: (type: string) =>
    data<{ dictLabel: string; dictValue: string }[]>(request({ url: `/system/dict/data/type/${enc(type)}`, method: 'get' })).then((rows) =>
      (rows ?? []).map((r) => ({ label: r.dictLabel, value: r.dictValue }))
    ),
  /** 表单自动带入的上下文：当前人员、主组织、班次、时间与本人上次填写的值 */
  formContext: (form: string) => data<FormContextData>(request({ url: '/api/ui/form-context', method: 'get', params: { form } })),
  remember: (form: string, values: Record<string, unknown>) =>
    data<Record<string, unknown>>(request({ url: `/api/ui/form-context/remembered/${enc(form)}`, method: 'put', data: values })),
  /** 本人的表单草稿（尚未提交的录入内容） */
  draftGet: (key: string) =>
    data<{ content: Record<string, unknown>; hash: string; updatedAt: string } | null>(request({ url: `/api/ui/drafts/${enc(key)}`, method: 'get', headers: { silentAuth: true } })),
  draftSave: (key: string, body: { content: Record<string, unknown>; hash: string }) =>
    data<unknown>(request({ url: `/api/ui/drafts/${enc(key)}`, method: 'put', data: body, headers: { repeatSubmit: false, silentAuth: true } })),
  draftDelete: (key: string) => data<unknown>(request({ url: `/api/ui/drafts/${enc(key)}`, method: 'delete' })),
  upload: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return data<{ ossId: string; fileName: string; url: string }>(
      request({ url: '/resource/oss/upload', method: 'post', data: fd, headers: { 'Content-Type': 'multipart/form-data' } })
    );
  }
};

export type FormApi = typeof formApi;

/** 组件只依赖这一小部分接口，测试中可直接替换。 */
export type RendererApi = Pick<FormApi, 'refSearch' | 'upload' | 'dict'> & Partial<Pick<FormApi, 'formContext' | 'remember' | 'draftGet' | 'draftSave' | 'draftDelete'>>;
