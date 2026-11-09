/**
 * fc-adapter：LS FormSchema ⇄ form-create 设计器规则（Rule[] + Options）。
 *
 * - 产品存储只保存 LS FormSchema；form-create 规则只在设计器内存在。
 * - LS 专有属性（Concept 引用、计算表达式、字典、正则、注册组件、只读/隐藏……）放在规则的 `lsExt` 中，
 *   表单级属性（编码、版本、Concept、规则、动作、节点策略、迁移策略）放在 options.lsMeta 中，设计器原样保留。
 * - 单向边界：form-create 的事件（on/$FNX）、远程数据（effect.fetch）、联动（control）、自定义样式/类名、原生 HTML、
 *   自带 computed 无法表达为 LS FormSchema，fromFcRule 遇到时报告 FcAdapterError，不会静默丢弃或写入存储。
 */
import type { FormField, FormSchema, LayoutNode, SubTable, Widget } from '@/types/lsView';

export type FcRule = Record<string, any>;
export interface FcOptions {
  form?: Record<string, any>;
  lsMeta?: Omit<FormSchema, 'layout' | 'fields' | 'subTables' | 'labelWidth'>;
  [k: string]: any;
}

export class FcAdapterError extends Error {
  constructor(public readonly issues: { path: string; message: string }[]) {
    super('设计器内容包含 LS FormSchema 不支持的特性：' + issues.map((i) => `${i.path} ${i.message}`).join('；'));
  }
}

/** LS 控件 → 设计器拖拽项（拖拽名 ls-<控件>）与画布上使用的 form-create 组件。 */
const FC_TYPE: Record<Widget, { type: string; props?: Record<string, any> }> = {
  input: { type: 'input' },
  textarea: { type: 'input', props: { type: 'textarea' } },
  number: { type: 'inputNumber' },
  money: { type: 'inputNumber', props: { precision: 2 } },
  date: { type: 'datePicker', props: { type: 'date' } },
  datetime: { type: 'datePicker', props: { type: 'datetime' } },
  switch: { type: 'switch' },
  select: { type: 'select' },
  radio: { type: 'radio' },
  checkbox: { type: 'checkbox' },
  cascader: { type: 'cascader' },
  remoteSelect: { type: 'select', props: { filterable: true, remote: true } },
  dict: { type: 'select' },
  rate: { type: 'rate' },
  userRef: { type: 'select', props: { filterable: true } },
  deptRef: { type: 'select', props: { filterable: true } },
  teamRef: { type: 'select', props: { filterable: true } },
  assetRef: { type: 'select', props: { filterable: true } },
  workRef: { type: 'select', props: { filterable: true } },
  materialRef: { type: 'select', props: { filterable: true } },
  pointRef: { type: 'select', props: { filterable: true } },
  conceptRef: { type: 'select', props: { filterable: true } },
  attachment: { type: 'upload' },
  image: { type: 'upload', props: { listType: 'picture-card' } },
  file: { type: 'upload' },
  sourceRef: { type: 'input' },
  confirm: { type: 'switch' },
  readonlyText: { type: 'input', props: { readonly: true } },
  computed: { type: 'input', props: { disabled: true } },
  expression: { type: 'input', props: { disabled: true } },
  registered: { type: 'input', props: { disabled: true } }
};

/** 由 form-create 原生属性承载的字段属性；其余进 lsExt。 */
const NATIVE_FIELD_KEYS = new Set(['key', 'label', 'widget', 'required', 'help', 'default', 'options', 'span', 'placeholder']);

const LAYOUT_DRAG: Record<string, string> = {
  row: 'fcRow',
  col: 'col',
  group: 'elCard',
  panel: 'elCard',
  tabs: 'elTabs',
  tab: 'elTabPane',
  collapse: 'elCollapse',
  divider: 'elDivider',
  text: 'text',
  description: 'text'
};

const clean = <T extends Record<string, any>>(o: T): T => {
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(o)) if (v !== undefined) out[k] = v;
  return out as T;
};

const deep = <T>(v: T): T => (v === undefined ? v : JSON.parse(JSON.stringify(v)));

// ---------------------------------------------------------------- LS → FC

export function toFcRule(schema: FormSchema): { rule: FcRule[]; options: FcOptions } {
  const fields = new Map(schema.fields.map((f) => [f.key, f]));
  const tables = new Map((schema.subTables ?? []).map((t) => [t.key, t]));
  const placed = new Set<string>();

  const node = (n: LayoutNode, parent?: LayoutNode): FcRule | undefined => {
    switch (n.type) {
      case 'field': {
        const f = fields.get(n.key!);
        if (!f) return undefined;
        placed.add(f.key);
        return fieldRule(f);
      }
      case 'subTable': {
        const t = tables.get(n.key!);
        if (!t) return undefined;
        placed.add(t.key);
        return tableRule(t, n);
      }
      case 'row':
        return { type: 'fcRow', _fc_drag_tag: 'fcRow', props: {}, children: kids(n) };
      case 'col':
        return { type: 'col', _fc_drag_tag: 'col', props: { span: n.span ?? 24 }, children: kids(n) };
      case 'group':
        return { type: 'elCard', _fc_drag_tag: 'elCard', props: { header: n.title ?? '' }, children: kids(n), lsExt: { node: 'group' } };
      case 'panel':
        if (parent?.type === 'collapse') {
          return clean({
            type: 'elCollapseItem',
            _fc_drag_tag: 'elCollapseItem',
            props: { title: n.title ?? '' },
            children: kids(n),
            lsExt: n.collapsed ? { collapsed: true } : undefined
          });
        }
        return { type: 'elCard', _fc_drag_tag: 'elCard', props: { header: n.title ?? '' }, children: kids(n), lsExt: { node: 'panel' } };
      case 'tabs':
        return { type: 'elTabs', _fc_drag_tag: 'elTabs', children: kids(n) };
      case 'tab':
        return { type: 'elTabPane', _fc_drag_tag: 'elTabPane', props: { label: n.title ?? '' }, children: kids(n) };
      case 'collapse':
        return { type: 'elCollapse', _fc_drag_tag: 'elCollapse', props: {}, children: kids(n) };
      case 'divider':
        return { type: 'elDivider', _fc_drag_tag: 'elDivider', props: {}, children: [n.title ?? ''] };
      case 'text':
      case 'description':
        return { type: 'div', _fc_drag_tag: 'text', native: true, children: [n.text ?? ''], lsExt: { node: n.type } };
      default:
        // 指标卡、关联摘要、关联列表：LS 自定义拖拽项，画布上显示标题
        return {
          type: 'div',
          _fc_drag_tag: 'ls-' + n.type,
          native: true,
          children: [n.title ?? n.metric ?? n.concept ?? ''],
          lsExt: clean({ node: n.type, key: n.key, title: n.title, metric: n.metric, concept: n.concept, via: n.via, columns: deep(n.columns) })
        };
    }
  };
  const kids = (n: LayoutNode) => (n.children ?? []).map((c) => node(c, n)).filter(Boolean) as FcRule[];

  const rule = schema.layout.map((n) => node(n)).filter(Boolean) as FcRule[];
  // 未放入布局的字段（只能是 hidden）放在末尾，保持定义不丢
  for (const f of schema.fields) if (!placed.has(f.key)) rule.push({ ...fieldRule(f), lsExt: { ...fieldRule(f).lsExt, unplaced: true } });

  const options: FcOptions = {
    // 渲染器默认标签宽度 100px；显式写入，避免设计器套用自己的默认值
    form: { labelWidth: (schema.labelWidth ?? 100) + 'px', labelPosition: 'right' },
    lsMeta: deep(
      clean({
        viewType: schema.viewType,
        formCode: schema.formCode,
        version: schema.version,
        conceptCode: schema.conceptCode,
        title: schema.title,
        rules: schema.rules,
        actions: schema.actions,
        nodePolicies: schema.nodePolicies,
        migration: schema.migration
      })
    ) as FcOptions['lsMeta'],
    submitBtn: false,
    resetBtn: false
  };
  return { rule, options };
}

function fieldRule(f: FormField): FcRule {
  const fc = FC_TYPE[f.widget] ?? { type: 'input' };
  const ext: Record<string, any> = { widget: f.widget };
  for (const [k, v] of Object.entries(f)) if (!NATIVE_FIELD_KEYS.has(k) && v !== undefined) ext[k] = deep(v);
  return clean({
    type: fc.type,
    _fc_drag_tag: 'ls-' + f.widget,
    field: f.key,
    title: f.label ?? '',
    info: f.help ?? '',
    $required: !!f.required,
    value: deep(f.default),
    props: clean({ ...(fc.props ?? {}), placeholder: f.placeholder }),
    options: f.options ? deep(f.options) : undefined,
    col: f.span != null ? { span: f.span } : undefined,
    lsExt: ext
  });
}

function tableRule(t: SubTable, n: LayoutNode): FcRule {
  return {
    type: 'tableForm',
    _fc_drag_tag: 'tableForm',
    field: t.key,
    title: t.label ?? '',
    // 设计器保存子表列的格式：props.columns[{label, rule}]（载入时展开为 tableFormColumn）
    props: { columns: t.fields.map((f) => ({ label: f.label ?? f.key, required: !!f.required, style: { width: '' }, rule: [fieldRule(f)] })) },
    children: [],
    lsExt: clean({ node: 'subTable', conceptCode: t.conceptCode, minRows: t.minRows, maxRows: t.maxRows, layoutTitle: n.title })
  };
}

// ---------------------------------------------------------------- FC → LS

const IGNORED = new Set(['_fc_id', 'name', 'display', 'hidden', 'effect', '_fc_drag_tag', 'native', 'key', 'slot', '_computed', 'emit', 'wrap']);

export function fromFcRule(rules: FcRule[], options: FcOptions = {}): FormSchema {
  const issues: { path: string; message: string }[] = [];
  const fields: FormField[] = [];
  const subTables: SubTable[] = [];

  const unsupported = (r: FcRule, path: string) => {
    if (r.on || r._on) issues.push({ path, message: '事件绑定（on）' });
    if (r.control || r._control) issues.push({ path, message: '联动（control）' });
    if (r.computed && Object.keys(r.computed).length) issues.push({ path, message: '设计器计算（computed）' });
    if (r.effect?.fetch) issues.push({ path, message: '远程数据（effect.fetch）' });
    if (r.style && Object.keys(r.style).some((k) => k !== 'width')) issues.push({ path, message: '自定义样式（style）' });
    if (r.class || r.className) issues.push({ path, message: '自定义类名' });
    if (r.native && !r.lsExt && r._fc_drag_tag !== 'text') issues.push({ path, message: '原生 HTML' });
    const fn = JSON.stringify(r.props ?? {});
    if (/\$FN[X]?:|function\s*\(|=>/.test(fn)) issues.push({ path, message: '函数属性' });
  };

  const toField = (r: FcRule, path: string): FormField => {
    unsupported(r, path);
    const ext = { ...(r.lsExt ?? {}) };
    const widget: Widget = ext.widget ?? guessWidget(r);
    delete ext.widget;
    delete ext.unplaced;
    const placeholder = r.props?.placeholder;
    return clean({
      key: r.field,
      label: r.title || undefined,
      widget,
      ...ext,
      required: r.$required ? true : ext.required,
      help: r.info || undefined,
      default: r.value,
      placeholder: placeholder || undefined,
      options: r.options && r.options.length ? deep(r.options) : undefined,
      span: r.col?.span
    }) as FormField;
  };

  const walk = (list: FcRule[], path: string, parent?: FcRule): LayoutNode[] => {
    const out: LayoutNode[] = [];
    list.forEach((r, i) => {
      if (typeof r === 'string') return;
      const p = `${path}/${i}`;
      if (r.type === 'tableForm') {
        unsupported(r, p);
        const ext = r.lsExt ?? {};
        // 两种形态：已保存的 props.columns[{rule}]，或设计器内存中的 tableFormColumn 子节点
        const columnRules: FcRule[][] = r.props?.columns?.length
          ? r.props.columns.map((c: FcRule) => c.rule ?? [])
          : (r.children ?? []).filter((c: FcRule) => typeof c === 'object' && c.type === 'tableFormColumn').map((c: FcRule) => c.children ?? []);
        const cols: FormField[] = columnRules.flatMap((list, j) =>
          list.filter((x: FcRule) => typeof x === 'object').map((x: FcRule) => toField(x, `${p}/${j}`))
        );
        subTables.push(
          clean({
            key: r.field,
            label: r.title || undefined,
            conceptCode: ext.conceptCode,
            minRows: ext.minRows,
            maxRows: ext.maxRows,
            fields: cols
          }) as SubTable
        );
        out.push(clean({ type: 'subTable', key: r.field, title: ext.layoutTitle }) as LayoutNode);
        return;
      }
      if (r.field !== undefined && r.type !== 'fcRow') {
        fields.push(toField(r, p));
        if (!r.lsExt?.unplaced) out.push({ type: 'field', key: r.field });
        return;
      }
      unsupported(r, p);
      const children = () => walk((r.children ?? []) as FcRule[], p, r);
      const ext = r.lsExt ?? {};
      switch (r._fc_drag_tag ?? r.type) {
        case 'fcRow':
          out.push({ type: 'row', children: children() });
          break;
        case 'col':
          out.push({ type: 'col', span: r.props?.span ?? 24, children: children() });
          break;
        case 'elCard':
          out.push(
            clean({ type: ext.node === 'panel' ? 'panel' : 'group', title: r.props?.header || undefined, children: children() }) as LayoutNode
          );
          break;
        case 'elTabs':
          out.push({ type: 'tabs', children: children() });
          break;
        case 'elTabPane':
          out.push(clean({ type: 'tab', title: r.props?.label || undefined, children: children() }) as LayoutNode);
          break;
        case 'elCollapse':
          out.push({ type: 'collapse', children: children() });
          break;
        case 'elCollapseItem':
          out.push(clean({ type: 'panel', title: r.props?.title || undefined, collapsed: ext.collapsed, children: children() }) as LayoutNode);
          break;
        case 'elDivider': {
          const t = (r.children ?? []).find((c: unknown) => typeof c === 'string');
          out.push(clean({ type: 'divider', title: t || undefined }) as LayoutNode);
          break;
        }
        case 'text':
          out.push({ type: ext.node === 'description' ? 'description' : 'text', text: String((r.children ?? [])[0] ?? '') });
          break;
        default:
          if (ext.node) {
            out.push(
              clean({
                type: ext.node,
                key: ext.key,
                title: ext.title,
                metric: ext.metric,
                concept: ext.concept,
                via: ext.via,
                columns: deep(ext.columns)
              }) as LayoutNode
            );
          } else {
            issues.push({ path: p, message: `设计器组件「${r.type}」没有对应的 LS 节点` });
          }
      }
    });
    void parent;
    return out;
  };

  const layout = walk(rules, '');
  if (issues.length) throw new FcAdapterError(issues);
  const meta = options.lsMeta ?? ({} as FcOptions['lsMeta']);
  const labelWidth = options.form?.labelWidth != null ? parseInt(String(options.form.labelWidth), 10) : undefined;
  return clean({
    viewType: 'FORM',
    formCode: meta!.formCode,
    version: meta!.version,
    conceptCode: meta!.conceptCode,
    title: meta!.title,
    labelWidth: labelWidth === undefined || Number.isNaN(labelWidth) || labelWidth === 100 ? undefined : labelWidth,
    layout,
    fields,
    subTables: subTables.length ? subTables : undefined,
    rules: deep(meta!.rules),
    actions: deep(meta!.actions),
    nodePolicies: deep(meta!.nodePolicies),
    migration: deep(meta!.migration)
  }) as FormSchema;
}

function guessWidget(r: FcRule): Widget {
  const tag = String(r._fc_drag_tag ?? '');
  if (tag.startsWith('ls-')) return tag.slice(3) as Widget;
  switch (r.type) {
    case 'input':
      return r.props?.type === 'textarea' ? 'textarea' : 'input';
    case 'inputNumber':
      return 'number';
    case 'datePicker':
      return r.props?.type === 'datetime' ? 'datetime' : 'date';
    case 'switch':
    case 'select':
    case 'radio':
    case 'checkbox':
    case 'cascader':
    case 'rate':
      return r.type;
    case 'upload':
      return 'attachment';
    default:
      return 'input';
  }
}

/** 语义等价比较用：去掉 undefined、空数组子节点，便于往返测试。 */
export function normalizeForm(s: FormSchema): FormSchema {
  const strip = (v: any): any => {
    if (Array.isArray(v)) return v.map(strip);
    if (v && typeof v === 'object') {
      const o: Record<string, any> = {};
      for (const [k, x] of Object.entries(v)) {
        if (x === undefined) continue;
        if (k === 'children' && Array.isArray(x) && x.length === 0) continue;
        o[k] = strip(x);
      }
      return o;
    }
    return v;
  };
  return strip(s);
}

export const LS_LAYOUT_DRAG = LAYOUT_DRAG;
