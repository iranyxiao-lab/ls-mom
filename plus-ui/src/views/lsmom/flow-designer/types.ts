import type { Ref } from 'vue';
import type { FlowNode, NodeType } from './model';

/** 节点树共享的上下文（递归组件之间传递）。 */
export interface TreeContext {
  selected: Ref<string | null>;
  issueKeys: Ref<Set<string>>;
  select: (key: string) => void;
  insert: (list: FlowNode[], index: number, type: NodeType) => void;
  remove: (key: string) => void;
  addBranch: (n: FlowNode) => void;
  assignee: (n: FlowNode) => string;
  opName: (code?: string) => string;
}

export interface OperationOption {
  operation: string;
  name: string;
}

export interface FormField {
  key: string;
  label: string;
}

/** 配置器依赖的数据与接口（页面注入，单元测试可替换）。 */
export interface FlowDesignerApi {
  validate: (def: any) => Promise<{ path: string; code: string; message: string; level?: 'ERROR' | 'WARNING' }[]>;
  preview: (def: any) => Promise<{ issues: any[]; bpmnXml: string }>;
  saveDraft: (def: any) => Promise<{ version: number; status: string }>;
  publish: (def: any) => Promise<{ version: number; status: string }>;
  history: (code: string) => Promise<{ version: number; status: string }[]>;
  get: (code: string, version: number) => Promise<{ version: number; status: string; definition: any }>;
  formFields: (code: string, version: number) => Promise<FormField[]>;
  parties: (keyword?: string) => Promise<{ id: number; name: string; orgName?: string }[]>;
}
