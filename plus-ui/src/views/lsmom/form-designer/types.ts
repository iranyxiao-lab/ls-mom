import type { FormSchema } from '@/types/lsView';
import type { CheckResult, PublishResult } from '@/api/lsmom/form';

export interface ConceptFieldInfo {
  code: string;
  label: string;
  type: string;
  refConcept?: string;
  dictType?: string;
  readPermission?: string;
  writePermission?: string;
  /** 子表字段所属子表键 */
  table?: string;
}

export interface DesignerApi {
  saveDraft: (schema: FormSchema) => Promise<CheckResult>;
  check: (code: string, version: number) => Promise<CheckResult>;
  publish: (code: string, version: number) => Promise<PublishResult>;
}
