/**
 * 读 Excel 第一个工作表的表头与前 50 行，单元格转成文本（日期为 yyyy-MM-dd，含时间时为 yyyy-MM-dd HH:mm）。
 * SheetJS 按需加载，不进首屏。
 */
export interface SheetSample {
  fileName: string;
  sheet: string;
  headers: string[];
  rows: string[][];
}

const pad = (n: number) => String(n).padStart(2, '0');
export function cellText(v: unknown): string {
  if (v == null) return '';
  if (v instanceof Date) {
    const d = `${v.getFullYear()}-${pad(v.getMonth() + 1)}-${pad(v.getDate())}`;
    return v.getHours() || v.getMinutes() ? `${d} ${pad(v.getHours())}:${pad(v.getMinutes())}` : d;
  }
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v).trim();
}

export async function readSheetSample(file: File, maxRows = 50): Promise<SheetSample> {
  const XLSX = await import('xlsx');
  const wb = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true, sheetRows: maxRows + 1 });
  const sheet = wb.SheetNames[0];
  if (!sheet) throw new Error('文件里没有工作表');
  const aoa = XLSX.utils.sheet_to_json<unknown[]>(wb.Sheets[sheet], { header: 1, raw: true, defval: null, blankrows: false });
  const [head = [], ...body] = aoa;
  const headers = head.map(cellText);
  const rows = body.slice(0, maxRows).map((r) => headers.map((_, i) => cellText(r[i])));
  return { fileName: file.name, sheet, headers, rows };
}
