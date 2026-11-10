import { afterEach, describe, expect, it, vi } from 'vitest';
import { preferMobile } from '@/mobile/mode';
import { currentShiftOf, isToday, sortToday } from '@/mobile/today';
import { clear, createStore } from 'idb-keyval';
import { discard, enqueue, flush, isNetworkError, loadOutbox, outbox } from '@/mobile/outbox';
import type { BoardWork } from '@/api/lsmom/productionModel';

const w = (id: number, over: Partial<BoardWork>): BoardWork =>
  ({
    id,
    code: 'W' + id,
    title: 't' + id,
    status: 'ASSIGNED',
    priority: 3,
    orgUnitId: 1,
    version: 0,
    risks: [],
    assignees: [],
    ...over
  }) as BoardWork;

afterEach(async () => {
  await clear(createStore('outbox-ui', 'items'));
  outbox.items = [];
});

describe('手机版：设备与用户类型', () => {
  it('手动选择优先；自动时窄屏或现场岗位在平板宽度用手机版', () => {
    expect(preferMobile('mobile', 1920, [])).toBe(true);
    expect(preferMobile('desktop', 390, ['operator'])).toBe(false);
    expect(preferMobile('auto', 390, ['admin'])).toBe(true);
    expect(preferMobile('auto', 900, ['operator'])).toBe(true);
    expect(preferMobile('auto', 900, ['team_leader'])).toBe(false);
    expect(preferMobile('auto', 1440, ['operator'])).toBe(false);
  });
});

describe('手机版：今日任务', () => {
  it('按时间判断班次', () => {
    expect(currentShiftOf(new Date('2026-11-11T07:59:00'))).toBe('NIGHT');
    expect(currentShiftOf(new Date('2026-11-11T08:00:00'))).toBe('DAY');
    expect(currentShiftOf(new Date('2026-11-11T16:30:00'))).toBe('EVENING');
  });
  it('只保留今天的未完成任务；本班次在前，再按班次、紧急程度排序', () => {
    const today = '2026-11-11';
    const list = [
      w(1, { shift: 'EVENING', shiftDate: today, priority: 1 }),
      w(2, { shift: 'DAY', shiftDate: today, priority: 3 }),
      w(3, { shift: 'DAY', shiftDate: today, priority: 1 }),
      w(4, { shift: 'NIGHT', shiftDate: today, priority: 2 }),
      w(5, { shift: 'DAY', shiftDate: '2026-11-12' }),
      w(6, { shift: 'DAY', shiftDate: today, status: 'DONE' }),
      w(7, { shift: 'NIGHT', shiftDate: '2026-11-10', status: 'IN_PROGRESS' })
    ];
    expect(isToday(list[6], today)).toBe(true);
    expect(sortToday(list, today, 'DAY').map((x) => x.id)).toEqual([3, 2, 4, 7, 1]);
  });
});

describe('手机版：离线队列', () => {
  it('按顺序重放；网络错误停下保留，业务拒绝标失败并说明原因，可丢弃', async () => {
    const sent: string[] = [];
    let offline = true;
    const sender = {
      execute: vi.fn(async (i: any) => {
        if (offline) throw new Error('Network Error');
        if (i.targetId === '9') throw Object.assign(new Error('目标对象不在你的数据范围内'), { code: 403, errorCode: 'ACCESS_DENIED' });
        sent.push(`${i.targetId}:${i.idempotencyKey}`);
        return { data: { reportId: 77 } };
      }),
      upload: vi.fn(async () => ({ ossId: 'o1' })),
      attach: vi.fn(async () => ({})),
      explain: vi.fn(async () => '任务已取消')
    };
    const a = await enqueue({
      title: '报工 1',
      operation: 'ls.core.Work.submitReport',
      targetId: '1',
      input: { outputQty: 1 },
      idempotencyKey: 'k1',
      photos: [{ name: 'p.jpg', blob: new Blob(['x'], { type: 'image/jpeg' }) }],
      attachTo: { objectType: 'ls.core.WorkReport', objectIdFrom: 'reportId' }
    });
    await enqueue({ title: '报工 9', operation: 'ls.core.Work.submitReport', targetId: '9', input: {} });
    await enqueue({ title: '报工 2', operation: 'ls.core.Work.submitReport', targetId: '2', input: {}, idempotencyKey: 'k2' });
    await flush(sender);
    expect(sent).toEqual([]);
    expect(outbox.items.every((i) => i.status === 'PENDING')).toBe(true);
    // 刷新页面后队列仍在（含照片）
    outbox.items = [];
    await loadOutbox();
    expect(outbox.items.map((i) => i.targetId)).toEqual(['1', '9', '2']);
    expect(outbox.items[0].photos?.[0].blob.size).toBe(1);
    offline = false;
    await flush(sender);
    expect(sent).toEqual(['1:k1', '2:k2']);
    expect(sender.attach).toHaveBeenCalledWith('o1', 'ls.core.WorkReport', 77);
    expect(outbox.items.map((i) => [i.targetId, i.status, i.error])).toEqual([['9', 'FAILED', '任务已取消']]);
    // 失败条目不会再重放
    await flush(sender);
    // 断网时第一条就停下（1 次），联网后 3 条各一次
    expect(sender.execute).toHaveBeenCalledTimes(1 + 3);
    await discard(outbox.items[0].id);
    expect(outbox.items).toEqual([]);
    expect(a.idempotencyKey).toBe('k1');
  });
  it('区分网络问题与业务拒绝', () => {
    expect(isNetworkError(new Error('Network Error'))).toBe(true);
    expect(isNetworkError(Object.assign(new Error('当前状态不能执行'), { code: 409 }))).toBe(false);
  });
});
