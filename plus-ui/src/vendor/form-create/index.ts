/**
 * form-create 设计器的唯一引入点（版本冻结：@form-create/designer 3.5.0、@form-create/element-ui 3.3.4，
 * 间接依赖在 pnpm-workspace.yaml overrides 中固定）。业务代码只经此处与 fc-adapter 使用设计器，不修改其核心；
 * 将来替换编辑器只需改这里和适配层，产品存储（LS FormSchema）与运行时渲染器不受影响。
 */
import FcDesigner from '@form-create/designer';

export { FcDesigner };
export const formCreate = (FcDesigner as any).formCreate;
