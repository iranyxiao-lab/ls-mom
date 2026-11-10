/**
 * modernize-ui-ux 4.6：子表从 Excel / WPS 粘贴。
 * 解析制表符文本（引号内换行）、按表头或列顺序对应、逐格转换；粘贴 30 行时字典不匹配的单元格标红并给出候选值，点候选即可改正；
 * 从某个单元格开始粘贴时覆盖该格起的区域；引用列按名称查找对象。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import { candidates, convertCell, headerMapping, parseTsv } from '@/utils/lsform/pasteGrid';

const form: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 1,
  conceptCode: 'acme.tool.LoanOrder',
  layout: [{ type: 'subTable', key: 'loanLine' }],
  fields: [],
  subTables: [
    {
      key: 'loanLine',
      label: '借出明细',
      conceptCode: 'acme.tool.LoanLine',
      maxRows: 100,
      fields: [
        { key: 'tool', label: '工器具', widget: 'input', required: true },
        { key: 'kind', label: '类别', widget: 'dict', dictType: 'ls_tool_kind' },
        { key: 'qty', label: '数量', widget: 'number', required: true, min: 1 },
        { key: 'keeper', label: '保管人', widget: 'userRef' },
        { key: 'due', label: '归还日期', widget: 'date' }
      ]
    }
  ]
};
const KINDS = [
  { label: '扳手类', value: 'WRENCH' },
  { label: '仪表类', value: 'METER' },
  { label: '绝缘工具', value: 'INSULATED' }
];

describe('pasteGrid', () => {
  it('解析制表符文本：引号内的换行与引号、末尾空行', () => {
    expect(parseTsv('a\tb\r\n"多\n行"\t"说""明"\r\n\r\n')).toEqual([
      ['a', 'b'],
      ['多\n行', '说"明']
    ]);
  });
  it('表头对应、转换与候选', () => {
    const fields = form.subTables![0].fields;
    expect(headerMapping(['工器具', '数量', '无关列'], fields)).toEqual(['tool', 'qty', null]);
    expect(headerMapping(['扳手', '2'], fields)).toBeNull();
    expect(convertCell(fields[1], '仪表类', KINDS).value).toBe('METER');
    expect(convertCell(fields[1], 'meter', KINDS).value).toBe('METER');
    const bad = convertCell(fields[1], '扳手', KINDS);
    expect(bad.error).toContain('不在可选范围内');
    expect(bad.candidates?.[0].label).toBe('扳手类');
    expect(convertCell(fields[2], '1,200', []).value).toBe(1200);
    expect(convertCell(fields[2], '两个', []).error).toContain('不是数字');
    expect(convertCell(fields[4], '2026/11/10', []).value).toBe('2026-11-10');
    expect(convertCell(fields[4], '46336', []).value).toBe('2026-11-10');
    expect(candidates('绝缘', KINDS)[0].value).toBe('INSULATED');
  });
});

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
});

async function flush(ms = 30) {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, ms));
}

function paste(el: Element, text: string) {
  const dt = new DataTransfer();
  dt.setData('text/plain', text);
  el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
}

function mount(initial: Record<string, any>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const model = ref<Record<string, any>>(initial);
  const api = {
    refSearch: vi.fn(async (b: any) => (b.keyword === '郑师傅' ? [{ id: 31, label: '郑师傅（甲班）' }] : [{ id: 40, label: '郑工' }])),
    upload: vi.fn(),
    dict: vi.fn(async () => KINDS)
  };
  app = createApp(
    defineComponent({
      setup: () => () => h(SchemaForm as any, { schema: form, api, modelValue: model.value, 'onUpdate:modelValue': (v: any) => (model.value = v) })
    })
  );
  app.use(ElementPlus).mount(host);
  return { model, el: host, api };
}

describe('SchemaForm 子表粘贴', () => {
  it('粘贴 30 行（带表头）：字典不匹配的单元格标红并给出候选，点候选改正', async () => {
    const { model, el, api } = mount({ loanLine: [] });
    await flush();
    const lines = ['工器具\t类别\t数量\t保管人\t归还日期'];
    for (let i = 1; i <= 30; i++) {
      const kind = i === 5 || i === 17 ? '扳手' : i % 2 ? '扳手类' : '仪表类';
      lines.push(`工具${i}\t${kind}\t${i === 9 ? '两个' : i}\t${i === 1 ? '郑师傅' : ''}\t2026-11-${String((i % 28) + 1).padStart(2, '0')}`);
    }
    paste(el.querySelector('[data-testid="paste-loanLine"]')!, lines.join('\r\n') + '\r\n');
    await flush(100);
    expect(model.value.loanLine).toHaveLength(30);
    expect(model.value.loanLine[0]).toMatchObject({ tool: '工具1', kind: 'WRENCH', qty: 1, keeper: 31, due: '2026-11-02' });
    expect(model.value.loanLine[1].kind).toBe('METER');
    expect(api.dict).toHaveBeenCalledTimes(1);

    const cell = (r: number, f: string) => el.querySelector(`[data-testid="cell-loanLine-${r}-${f}"]`) as HTMLElement;
    for (const r of [4, 16]) {
      expect(cell(r, 'kind').className).toContain('is-error');
      expect(cell(r, 'kind').textContent).toContain(`第 ${r + 1} 行：类别「扳手」不在可选范围内`);
      expect(el.querySelector(`[data-testid="cands-loanLine-${r}-kind"]`)?.textContent).toContain('扳手类');
    }
    expect(cell(8, 'qty').className).toContain('is-error');
    expect(cell(1, 'kind').className).not.toContain('is-error');
    expect(document.querySelector('.el-message')?.textContent).toContain('已粘贴 30 行，3 处需要修改');

    // 点候选改正
    (el.querySelector('[data-testid="cands-loanLine-4-kind"] button') as HTMLElement).click();
    await flush();
    expect(model.value.loanLine[4].kind).toBe('WRENCH');
    expect(cell(4, 'kind').className).not.toContain('is-error');
    expect(el.querySelector('[data-testid="cands-loanLine-4-kind"]')).toBeNull();
  });

  it('从单元格开始粘贴：按列顺序覆盖该格起的区域，不够的行自动新增；单个值按普通输入处理', async () => {
    const { model, el } = mount({
      loanLine: [
        { tool: 'A', qty: 1 },
        { tool: 'B', qty: 2 }
      ]
    });
    await flush();
    const start = el.querySelector('[data-testid="cell-loanLine-1-kind"] input, [data-testid="cell-loanLine-1-kind"] [tabindex]') as HTMLElement;
    paste(start ?? el.querySelector('[data-testid="cell-loanLine-1-kind"]')!, '仪表类\t5\n绝缘工具\t6');
    await flush(100);
    expect(model.value.loanLine).toHaveLength(3);
    expect(model.value.loanLine[0]).toMatchObject({ tool: 'A', qty: 1 });
    expect(model.value.loanLine[1]).toMatchObject({ tool: 'B', kind: 'METER', qty: 5 });
    expect(model.value.loanLine[2]).toMatchObject({ kind: 'INSULATED', qty: 6 });
    // 新增的第 3 行没有工器具：必填问题标出
    expect((el.querySelector('[data-testid="cell-loanLine-2-tool"]') as HTMLElement).className).toContain('is-error');

    const input = el.querySelector('[data-testid="cell-loanLine-0-tool"] input')!;
    const dt = new DataTransfer();
    dt.setData('text/plain', '单个值');
    const ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });
    input.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(false);
  });
});
