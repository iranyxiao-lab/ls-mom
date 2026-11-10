import type { FormField, SubTable } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import type { Access } from '@/utils/lsform/fieldPolicy';

/** 渲染上下文（由 SchemaForm 提供，递归共享）。 */
export interface RenderCtx {
  fields: Map<string, FormField>;
  tables: Map<string, SubTable>;
  model: Record<string, any>;
  access: Record<string, Access>;
  errors: Record<string, string>;
  api: RendererApi;
  set: (key: string, v: unknown) => void;
  /** 设置或清除单元格问题（如粘贴后的逐格校验结果；null 为清除） */
  setCellErrors?: (e: Record<string, string | null>) => void;
  orgId?: number;
  only?: Set<string>;
  single?: boolean;
  capture?: boolean;
  metrics?: Record<string, unknown>;
  related?: Record<string, unknown>;
  /** 预填字段的来源（上次填写 / 自动带入） */
  origin?: Record<string, import('@/utils/lsform/smartDefaults').FillOrigin>;
}

/** 附件/图片/文件：值为 [{ossId, name, url}]（存 jsonb），文件经 OSS 上传。 */
export interface FileRef {
  ossId: string;
  name: string;
  url: string;
}
