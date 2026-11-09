/**
 * 设计器控件面板：MVP 控件集全部注册为 ls-<控件> 拖拽项，分基础/选择/业务引用/证据/展示/高级六组；
 * 结构控件（栅格、分组、页签、折叠、子表、分隔线、说明）复用设计器内置项。属性面板只开放 LS 能表达的属性。
 */
import type { Widget } from '@/types/lsView';

type T = (k: string) => string;

export interface LsMenu {
  name: string;
  title: string;
  list: { name: string; label: string; icon: string }[];
}

const GROUPS: { name: string; title: string; items: [Widget, string][] }[] = [
  {
    name: 'ls-basic',
    title: '基础',
    items: [
      ['input', '单行文本'],
      ['textarea', '多行文本'],
      ['number', '数字'],
      ['money', '金额'],
      ['date', '日期'],
      ['datetime', '日期时间'],
      ['switch', '是/否'],
      ['dict', '字典'],
      ['rate', '评分']
    ]
  },
  {
    name: 'ls-choice',
    title: '选择',
    items: [
      ['radio', '单选'],
      ['checkbox', '多选'],
      ['select', '下拉'],
      ['cascader', '级联'],
      ['remoteSelect', '远程搜索']
    ]
  },
  {
    name: 'ls-ref',
    title: '业务引用',
    items: [
      ['userRef', '用户'],
      ['deptRef', '部门'],
      ['teamRef', '班组'],
      ['assetRef', '设备'],
      ['workRef', '工作项'],
      ['materialRef', '物料'],
      ['pointRef', '点位'],
      ['conceptRef', '通用引用']
    ]
  },
  {
    name: 'ls-evidence',
    title: '证据',
    items: [
      ['attachment', '附件'],
      ['image', '图片'],
      ['file', '文件'],
      ['sourceRef', '来源引用'],
      ['confirm', '确认记录']
    ]
  },
  { name: 'ls-display', title: '展示', items: [['readonlyText', '只读文本']] },
  {
    name: 'ls-advanced',
    title: '高级',
    items: [
      ['computed', '计算字段'],
      ['expression', '受控表达式'],
      ['registered', '注册组件']
    ]
  }
];

const FC_BASE: Partial<Record<Widget, { type: string; props?: Record<string, unknown> }>> = {
  textarea: { type: 'input', props: { type: 'textarea' } },
  number: { type: 'inputNumber' },
  money: { type: 'inputNumber', props: { precision: 2 } },
  date: { type: 'datePicker', props: { type: 'date' } },
  datetime: { type: 'datePicker', props: { type: 'datetime' } },
  switch: { type: 'switch' },
  confirm: { type: 'switch' },
  radio: { type: 'radio' },
  checkbox: { type: 'checkbox' },
  select: { type: 'select' },
  dict: { type: 'select' },
  cascader: { type: 'cascader' },
  rate: { type: 'rate' },
  attachment: { type: 'upload' },
  file: { type: 'upload' },
  image: { type: 'upload', props: { listType: 'picture-card' } }
};

let seq = 0;
const fieldKey = (w: string) => `${w.replace(/Ref$/, '')}${Date.now().toString(36).slice(-4)}${(++seq).toString(36)}`;

export const LS_MENUS: LsMenu[] = GROUPS.map((g) => ({
  name: g.name,
  title: g.title,
  list: g.items.map(([w, label]) => ({ name: 'ls-' + w, label, icon: 'icon-input' }))
}));

/** LS 结构控件：指标卡、关联摘要、关联列表（画布显示标题，属性在扩展面板配置）。 */
const STRUCT: [string, string][] = [
  ['metricCard', '指标卡'],
  ['relatedSummary', '关联摘要'],
  ['relatedList', '关联列表']
];

export function lsDragRules() {
  const fields = GROUPS.flatMap((g) =>
    g.items.map(([w, label]) => {
      const base = FC_BASE[w] ?? { type: w === 'readonlyText' ? 'input' : w.endsWith('Ref') || w === 'remoteSelect' ? 'select' : 'input' };
      const hasOptions = ['radio', 'checkbox', 'select', 'cascader'].includes(w);
      return {
        name: 'ls-' + w,
        label,
        icon: 'icon-input',
        menu: g.name,
        input: true,
        validate: false,
        rule: ({ t }: { t: T }) => {
          void t;
          return {
            type: base.type,
            field: fieldKey(w),
            title: label,
            info: '',
            $required: false,
            props: { ...(base.props ?? {}), ...(w === 'computed' || w === 'expression' || w === 'registered' ? { disabled: true } : {}) },
            ...(hasOptions
              ? {
                  options: [
                    { value: '1', label: '选项1' },
                    { value: '2', label: '选项2' }
                  ]
                }
              : {}),
            lsExt: { widget: w }
          };
        },
        props: () => []
      };
    })
  );
  const structs = STRUCT.map(([node, label]) => ({
    name: 'ls-' + node,
    label,
    icon: 'icon-card',
    menu: 'ls-display',
    rule: () => ({ type: 'div', native: true, children: [label], lsExt: { node, title: label } }),
    props: () => []
  }));
  return [...fields, ...structs];
}

export const LS_STRUCT_MENU_ITEMS = STRUCT.map(([node, label]) => ({ name: 'ls-' + node, label, icon: 'icon-card' }));
