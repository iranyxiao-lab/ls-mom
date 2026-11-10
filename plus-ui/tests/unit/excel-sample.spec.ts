import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';
import { cellText, readSheetSample } from '@/utils/excelSample';

describe('Excel 样例读取', () => {
  it('单元格转文本：日期、日期时间、布尔、数字', () => {
    expect(cellText(new Date(2026, 10, 3))).toBe('2026-11-03');
    expect(cellText(new Date(2026, 10, 3, 8, 5))).toBe('2026-11-03 08:05');
    expect(cellText(true)).toBe('是');
    expect(cellText(12.5)).toBe('12.5');
    expect(cellText(null)).toBe('');
  });

  it('只取表头与前 50 行，列与表头对齐', async () => {
    const aoa: unknown[][] = [['日期', '设备', '结果']];
    for (let i = 0; i < 80; i++) aoa.push([new Date(2026, 9, 1 + (i % 28)), `P-${i}`, i % 2 ? '正常' : '异常']);
    aoa.push(['', '', '']);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(aoa, { cellDates: true }), '点检');
    const buf = XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
    const s = await readSheetSample(new File([buf], '点检.xlsx'));
    expect(s.sheet).toBe('点检');
    expect(s.headers).toEqual(['日期', '设备', '结果']);
    expect(s.rows).toHaveLength(50);
    expect(s.rows[0]).toEqual(['2026-10-01', 'P-0', '异常']);
  });
});
