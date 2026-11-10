import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const enc = encodeURIComponent;

export interface PackDependency {
  code: string;
  version: string;
}

export interface PackSummary {
  code: string;
  name: string;
  version: string;
  previousVersion?: string;
  status: 'INSTALLING' | 'ENABLED' | 'DISABLED' | 'FAILED' | 'UNINSTALLED';
  standard: boolean;
  owner?: string;
  requester?: string;
  reviewDue?: string;
  reviewStatus: 'OK' | 'REVIEW_DUE' | 'SUGGEST_RETIRE';
  dependsOn: PackDependency[];
  dependents: string[];
  entries: { name: string; path: string }[];
  description?: string;
  installedAt?: string;
  disabledAt?: string;
  error?: string;
  uses90d: number;
  lastUsedAt?: string;
  failedTasks: number;
  latestVersion: string;
  upgradeAvailable: boolean;
  drifted: boolean;
  driftCount: number;
  items?: { kind: string; code: string; version: number | ''; path: string }[];
  changeSets?: { id: number; title: string; status: string; version: string }[];
}

export interface DriftDiff {
  path: string;
  expected: unknown;
  actual: unknown;
}

export interface DriftItem {
  kind: string;
  code: string;
  path: string;
  state: 'SAME' | 'CHANGED' | 'MISSING';
  expectedVersion?: number;
  actualVersion?: number;
  diffs: DriftDiff[];
}

export interface DriftReport {
  pack: string;
  version: string;
  drifted: boolean;
  items: DriftItem[];
}

export interface TestCase {
  name: string;
  passed: boolean;
  message?: string;
}

export interface TestReport {
  pack: string;
  version: string;
  passed: boolean;
  regression: TestCase[];
  privilege: TestCase[];
}

export type Stage = 'VALIDATE' | 'SANDBOX' | 'REGRESSION' | 'PRIVILEGE' | 'UPGRADE';

export interface StageResult {
  stage: Stage;
  status: 'PASSED' | 'FAILED';
  artifactSha: string;
  detail: Record<string, any>;
  runBy?: number;
  runAt: string;
}

export interface ChangeSet {
  id: number;
  pack: string;
  title: string;
  status: 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'APPLIED' | 'REJECTED';
  breaking: boolean;
  source: string;
  items: Record<string, any>[];
  artifactSha: string;
  version: string;
  createdBy?: number;
  reviewedBy?: number;
  reviewNote?: string;
  createdAt: string;
  releasedAt?: string;
  stages: StageResult[];
}

export interface Gate {
  ready: boolean;
  problems: string[];
}

export interface ItemSource {
  type?: string;
  name?: string;
  field?: string;
  path?: string;
}

export interface ProvenanceElement {
  kind: string;
  parent?: string;
  concept?: string;
  source?: ItemSource;
  fields?: Record<string, ItemSource>;
}

export interface ManualEdit {
  at: string;
  by: string;
  path: string;
  reason: string;
  lines?: number;
}

export interface ManualRequired {
  path: string;
  kind: 'MANUAL' | 'BLOCKED';
  reason: string;
  status: 'OPEN' | 'RESOLVED';
  resolution?: string;
  resolvedBy?: string;
}

/** 生成 / 转换来源记录；手写 Pack 的 provenance 为 null。 */
export interface Provenance {
  pack: string;
  version: string;
  provenance?: null;
  sourceType?: 'REQUIREMENT' | 'FRAPPE' | 'LEGACY_SCHEMA' | 'MANUAL';
  source?: { app: string; version?: string; path?: string; ref?: string };
  license?: string;
  converter?: { name: string; version: string };
  model?: { name: string; version: string } | null;
  generatedAt?: string;
  manualEdits?: ManualEdit[];
  manualRequired?: ManualRequired[];
  elements?: Record<string, ProvenanceElement>;
}

export interface PackApi {
  list(): Promise<PackSummary[]>;
  get(code: string): Promise<PackSummary>;
  enable(code: string): Promise<void>;
  disable(code: string): Promise<void>;
  exportData(code: string): Promise<Record<string, any>>;
  test(code: string): Promise<TestReport>;
  drift(code: string): Promise<DriftReport>;
  driftToChangeSet(code: string): Promise<ChangeSet>;
  runReview(): Promise<unknown>;
  changeSets(pack?: string): Promise<ChangeSet[]>;
  gate(id: number): Promise<Gate>;
  submit(archiveBase64: string): Promise<ChangeSet>;
  approve(id: number, approve: boolean, note?: string): Promise<ChangeSet>;
  release(id: number): Promise<{ code: string; version: string; status: string }>;
  /** 后台发布（任务中心）：返回任务编号。 */
  releaseAsync?(id: number): Promise<number>;
  provenance(code: string): Promise<Provenance>;
}

const noRepeat = { repeatSubmit: false };

export const packApi: PackApi = {
  list: () => data(request({ url: '/api/packs', method: 'get' })),
  get: (code) => data(request({ url: `/api/packs/${enc(code)}`, method: 'get' })),
  enable: (code) => data(request({ url: `/api/packs/${enc(code)}/enable`, method: 'post' })),
  disable: (code) => data(request({ url: `/api/packs/${enc(code)}/disable`, method: 'post' })),
  exportData: (code) => data(request({ url: `/api/packs/${enc(code)}/export`, method: 'get' })),
  test: (code) => data(request({ url: `/api/packs/${enc(code)}/test`, method: 'post', headers: noRepeat, timeout: 120000 })),
  drift: (code) => data(request({ url: `/api/packs/${enc(code)}/drift`, method: 'get' })),
  driftToChangeSet: (code) => data(request({ url: `/api/packs/${enc(code)}/drift/changeset`, method: 'post' })),
  runReview: () => data(request({ url: '/api/packs/review/run', method: 'post', headers: noRepeat })),
  changeSets: (pack) => data(request({ url: '/api/changesets', method: 'get', params: { pack } })),
  gate: (id) => data(request({ url: `/api/changesets/${id}/gate`, method: 'get' })),
  submit: (archive) => data(request({ url: '/api/changesets', method: 'post', data: { archive }, timeout: 120000 })),
  approve: (id, approve, note) => data(request({ url: `/api/changesets/${id}/approve`, method: 'post', data: { approve, note } })),
  release: (id) => data(request({ url: `/api/changesets/${id}/release`, method: 'post', timeout: 300000 })),
  releaseAsync: (id) => data(request({ url: `/api/changesets/${id}/release/async`, method: 'post' })),
  provenance: (code) => data(request({ url: `/api/packs/${enc(code)}/provenance`, method: 'get' }))
};
