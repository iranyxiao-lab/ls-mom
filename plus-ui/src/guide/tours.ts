/**
 * 新手引导定义：工作台、设计器、移动报工。步骤按 `data-tour` 锚点定位，锚点不在页面上时该步居中显示。
 * `pref` 是服务端偏好里记录"看完 / 跳过"的键；`sop` 是帮助菜单「操作手册」在知识中心里检索的主题。
 */
export type TourKey = 'workbench' | 'designer' | 'mobileReport';

export interface TourStep {
  /** `data-tour` 锚点；为空时居中 */
  anchor?: string;
  title: string;
  text: string;
}

export interface TourDef {
  key: TourKey;
  pref: 'tourWorkbench' | 'tourDesigner' | 'tourMobileReport';
  name: string;
  /** 匹配的路由路径 */
  path: RegExp;
  /** 页面根元素（出现后才自动开始） */
  root: string;
  sop: string;
  steps: TourStep[];
}

export const TOURS: TourDef[] = [
  {
    key: 'workbench',
    pref: 'tourWorkbench',
    name: '协同工作中心',
    path: /^\/lsmom\/workbench/,
    root: '[data-testid="work-center"]',
    sop: '协同工作中心 待办办理',
    steps: [
      { title: '欢迎使用协同工作中心', text: '这里汇总分给你的审批、确认和消息。下面用几步介绍常用操作，随时可以跳过。' },
      { anchor: 'wc-tabs', title: '按类别查看', text: '「我的待办」是要你处理的；「已办」「我发起的」「抄送我的」用来查进度。数字是未处理条数，红色表示有超时。' },
      { anchor: 'wc-filters', title: '筛选', text: '按业务类型、紧急程度或关键词找到要处理的单据。' },
      { anchor: 'wc-list', title: '原地办理', text: '点一条待办，右侧抽屉里直接填写意见并办理；办完会自动打开下一条，不用来回切换。' }
    ]
  },
  {
    key: 'designer',
    pref: 'tourDesigner',
    name: '表单设计器',
    path: /^\/lsmom\/form-designer/,
    root: '[data-testid="designer"]',
    sop: '表单设计器 发布',
    steps: [
      { title: '欢迎使用表单设计器', text: '在这里拖拽字段搭建业务表单，校验通过后发布，台账和移动端会使用新版本。' },
      { anchor: 'fd-canvas', title: '拖入字段', text: '从左侧把字段拖到画布上；点中字段后在右侧设置名称、必填、默认值和说明（说明会作为填写时的悬浮提示）。' },
      { anchor: 'fd-check', title: '先校验', text: '「校验」会检查字段绑定、规则和必填项，问题会列出并可点击定位。' },
      { anchor: 'fd-publish', title: '发布', text: '发布后生成新版本，已提交的单据仍按原版本显示。删除字段等破坏性变更需要他人评审。' }
    ]
  },
  {
    key: 'mobileReport',
    pref: 'tourMobileReport',
    name: '移动报工',
    path: /^\/production\/report/,
    root: '[data-testid="mobile-report"]',
    sop: '移动报工 填报',
    steps: [
      { title: '欢迎使用移动报工', text: '在手机上查看分给你的生产工作，开始、填报进度并提交确认。' },
      { anchor: 'mr-list', title: '我的工作', text: '这里列出分给你的工作，点一条进入填报。有草稿的会标出「有草稿」。' },
      { title: '填报与提交', text: '进入后先点「开始工作」，再填写进度、人工、产出并可拍照；断网时会存成草稿，联网后再提交。' }
    ]
  }
];

export function tourFor(path: string): TourDef | undefined {
  return TOURS.find((t) => t.path.test(path));
}
