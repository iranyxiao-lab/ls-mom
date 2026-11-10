/**
 * 操作与性能预算实测记录（acceptance-walkthrough 6.4）：追加写入 runs/<ACC_RUN>/budgets.json，由 tools/report.ts 与预算对比。
 * budget 为 null 表示只记录不判定（spec 未规定该项预算，如完成耗时）。
 */
import fs from 'node:fs';
import path from 'node:path';

// 结果目录与 playwright.config.ts 一致（配置进程设置的 ACC_RUN 会传给工作进程）
const RUN_DIR = path.join(import.meta.dirname, '../../runs', process.env.ACC_RUN ?? 'adhoc');

export interface BudgetItem {
  id: string;
  name: string;
  kind: 'click' | 'time' | 'perf';
  budget: number | null;
  actual: number | null;
  unit: string;
  steps?: string[];
  caseId?: string;
}

export function record(item: BudgetItem) {
  const file = path.join(RUN_DIR, 'budgets.json');
  fs.mkdirSync(RUN_DIR, { recursive: true });
  const doc: { items: BudgetItem[] } = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : { items: [] };
  doc.items = [...doc.items.filter((x) => x.id !== item.id), item];
  fs.writeFileSync(file, JSON.stringify(doc, null, 2));
}
