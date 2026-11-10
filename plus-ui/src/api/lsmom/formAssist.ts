import request from '@/utils/request';
import type { FormSchema } from '@/types/lsView';

/** 设计助手返回的补丁项（服务端已逐条校验，valid=false 的不能应用）。 */
export type AssistOp =
  | {
      op: 'addRule';
      rule: { code: string; level?: string; when?: string; assert: string; field?: string; message: string };
      valid: boolean;
      issue?: string;
    }
  | { op: 'updateField'; key: string; set: Record<string, unknown>; valid: boolean; issue?: string }
  | { op: 'addField'; field: { key: string; label?: string; widget?: string; required?: boolean }; valid: boolean; issue?: string };

export interface AssistProposal {
  summary: string;
  ops: AssistOp[];
  issues: string[];
  model: string;
  modelVersion: string;
  requestDigest: string;
}

export const formAssistApi = {
  propose: (schema: FormSchema, conceptFields: { code: string; label: string; type: string }[], instruction: string) =>
    request({ url: '/api/form/assist', method: 'post', data: { schema, conceptFields, instruction }, timeout: 90000 }).then(
      (r: any) => r.data as AssistProposal
    )
};
