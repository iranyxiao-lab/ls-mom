<template>
  <div class="schema-print" :class="['schema-print--' + (schema.paper ?? 'A4'), 'schema-print--' + (schema.orientation ?? 'portrait')]" data-testid="schema-print">
    <header class="schema-print__header">
      <h2>{{ schema.header?.title ?? title }}</h2>
      <div v-if="schema.header?.subtitle" class="schema-print__sub">{{ schema.header.subtitle }}</div>
    </header>
    <section v-for="(s, i) in schema.sections" :key="i" class="schema-print__section">
      <h3 v-if="s.title">{{ s.title }}</h3>
      <table v-if="s.fields.length" class="schema-print__grid">
        <tbody>
          <tr v-for="(row, r) in chunk(s.fields, s.columns ?? 2)" :key="r">
            <template v-for="k in row" :key="k">
              <th>{{ labels?.[k] ?? k }}</th>
              <td>{{ text(k) }}</td>
            </template>
          </tr>
        </tbody>
      </table>
      <table v-if="s.subTable" class="schema-print__lines">
        <thead>
          <tr>
            <th>#</th>
            <th v-for="c in childColumns(s.subTable)" :key="c">{{ labels?.[childKey(s.subTable) + '.' + c] ?? c }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(line, j) in lines(s.subTable)" :key="j">
            <td>{{ j + 1 }}</td>
            <td v-for="c in childColumns(s.subTable)" :key="c">{{ cell(line[c]) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <section v-if="schema.signatures?.length" class="schema-print__signs">
      <div v-for="sg in schema.signatures" :key="sg.label" class="schema-print__sign">{{ sg.label }}：<span class="schema-print__line" /></div>
    </section>
    <footer class="schema-print__footer">
      <span>{{ schema.footer?.text }}</span>
      <span>模板 {{ schema.formCode }} v{{ schema.version }}（{{ schema.templateVersion }}）</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import type { PrintSchema } from '@/types/lsView';

/** 打印视图：按 PrintSchema 生成纸面版式（页眉、分栏字段、明细表、签字区、页脚含模板版本），配合浏览器打印。 */
const props = defineProps<{ schema: PrintSchema; record: Record<string, any>; labels?: Record<string, string>; title?: string }>();

const chunk = <T,>(a: T[], n: number) => Array.from({ length: Math.ceil(a.length / n) }, (_, i) => a.slice(i * n, i * n + n));
const childKey = (concept: string) => {
  const simple = concept.substring(concept.lastIndexOf('.') + 1);
  return simple.charAt(0).toLowerCase() + simple.slice(1);
};
const lines = (concept: string) => ((props.record[childKey(concept)] as Record<string, unknown>[]) ?? []);
const childColumns = (concept: string) => {
  const keys = new Set<string>();
  lines(concept).forEach((r) => Object.keys(r).forEach((k) => !['id', 'lineNo', 'parentId'].includes(k) && keys.add(k)));
  return [...keys];
};
const cell = (v: unknown) => (v === null || v === undefined ? '' : typeof v === 'boolean' ? (v ? '是' : '否') : String(v));
const text = (k: string) => {
  const label = props.record[k + 'Label'];
  return label !== undefined && label !== null ? String(label) : cell(props.record[k]);
};
</script>

<style scoped>
.schema-print {
  background: #fff;
  color: #000;
  padding: 16mm;
  margin: 0 auto;
  font-size: 12pt;
}
.schema-print--A4.schema-print--portrait {
  width: 210mm;
  min-height: 297mm;
}
.schema-print--A4.schema-print--landscape {
  width: 297mm;
}
.schema-print--A5 {
  width: 148mm;
}
.schema-print__header {
  text-align: center;
  margin-bottom: 12px;
}
.schema-print__grid,
.schema-print__lines {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 12px;
}
.schema-print__grid th,
.schema-print__grid td,
.schema-print__lines th,
.schema-print__lines td {
  border: 1px solid #333;
  padding: 4px 6px;
  text-align: left;
}
.schema-print__grid th {
  width: 18%;
  background: #f5f5f5;
}
.schema-print__signs {
  display: flex;
  gap: 32px;
  margin-top: 24px;
}
.schema-print__line {
  display: inline-block;
  width: 120px;
  border-bottom: 1px solid #000;
}
.schema-print__footer {
  display: flex;
  justify-content: space-between;
  margin-top: 24px;
  font-size: 9pt;
  color: #555;
}
@media print {
  .schema-print {
    padding: 0;
    width: auto;
    min-height: 0;
  }
}
</style>
