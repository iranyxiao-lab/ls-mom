import { describe, expect, it } from 'vitest';
import { applyAssistOps, describeOp } from '@/views/lsmom/form-designer/assistPatch';
import type { AssistOp } from '@/api/lsmom/formAssist';

const schema: any = {
  viewType: 'FORM',
  formCode: 'x',
  version: 1,
  conceptCode: 'c',
  layout: [{ type: 'field', key: 'symptom' }],
  fields: [{ key: 'symptom', label: '现象', widget: 'textarea' }],
  rules: []
};
const ops: AssistOp[] = [
  {
    op: 'addRule',
    valid: true,
    rule: { code: 'symptomRequired', when: "$model.severity == 'HIGH'", assert: 'has($model.symptom)', field: 'symptom', message: '高级别须填现象' }
  },
  { op: 'updateField', valid: true, key: 'symptom', set: { help: '高级别必填' } },
  { op: 'addField', valid: true, field: { key: 'location', label: '位置' } },
  { op: 'updateField', valid: false, issue: '字段不在表单上', key: 'ghost', set: { required: true } }
];

describe('设计助手补丁', () => {
  it('只应用合法补丁，不改原草稿', () => {
    const s = applyAssistOps(schema, ops);
    expect(s.rules).toHaveLength(1);
    expect(s.rules![0]).toMatchObject({ code: 'symptomRequired', level: 'L1', field: 'symptom' });
    expect(s.fields.find((f) => f.key === 'symptom')!.help).toBe('高级别必填');
    expect(s.layout.map((l: any) => l.key)).toEqual(['symptom', 'location']);
    expect(schema.rules).toHaveLength(0);
    expect(schema.fields).toHaveLength(1);
  });

  it('差异说明：新增规则、修改前后', () => {
    expect(describeOp(schema, ops[0])).toMatchObject({ kind: 'add', target: '规则 symptomRequired' });
    const d = describeOp(schema, ops[1]);
    expect(d.kind).toBe('change');
    expect(d.lines).toEqual([
      { op: '-', text: 'help: null' },
      { op: '+', text: 'help: "高级别必填"' }
    ]);
  });
});
