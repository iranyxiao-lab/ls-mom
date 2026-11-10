import { afterEach, describe, expect, it, vi } from 'vitest';
import { ElNotification } from 'element-plus';
import 'element-plus/dist/index.css';

const server: any[] = [];
const notified = vi.fn(async () => ({}));
vi.mock('@/api/lsmom/tasks', () => ({
  taskApi: { mine: vi.fn(async () => server.map((t) => ({ ...t }))), get: vi.fn(), notified: (id: number) => notified(id) }
}));

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
import { followTask, refreshTasks, resetTaskCenter, startLocalTask, taskCenter, taskSummary } from '@/utils/taskCenter';

const notices = () => [...document.querySelectorAll('.ls-task-notice')].map((n) => n.textContent ?? '');
const task = (over: any) => ({
  id: 1,
  kind: 'IMPORT_CHECK',
  title: '导入检查：借还单（a.xlsx）',
  status: 'RUNNING',
  total: 5000,
  done: 100,
  failed: 0,
  createdAt: new Date().toISOString(),
  notified: false,
  ...over
});

afterEach(async () => {
  ElNotification.closeAll();
  await wait(400);
  resetTaskCenter();
  server.length = 0;
  notified.mockClear();
});

describe('任务中心', () => {
  it('页面跟进时完成不弹通知，由页面展示结果并标记已通知', async () => {
    server.push(task({}));
    const seen: string[] = [];
    const f = followTask(1, (t) => seen.push(t.status));
    await refreshTasks();
    server[0] = task({
      status: 'DONE',
      done: 5000,
      finishedAt: new Date().toISOString(),
      result: { report: { total: 5000, valid: 4990, invalid: 10, errors: [] } }
    });
    await refreshTasks();
    expect(seen.at(-1)).toBe('DONE');
    await wait(20);
    expect(notified).toHaveBeenCalledWith(1);
    await wait(20);
    expect(notices()).toHaveLength(0);
    f.stop();
  });

  it('离开页面（停止跟进）后完成：弹出完成通知，摘要带结果，只弹一次', async () => {
    server.push(task({}));
    const f = followTask(1, () => undefined);
    await refreshTasks();
    f.stop();
    server[0] = task({
      status: 'DONE',
      done: 5000,
      finishedAt: new Date().toISOString(),
      result: { report: { total: 5000, valid: 4990, invalid: 10 } }
    });
    await refreshTasks();
    await refreshTasks();
    await wait(20);
    expect(notices()).toHaveLength(1);
    expect(notices()[0]).toContain('导入检查：借还单（a.xlsx） 已完成');
    expect(notices()[0]).toContain('共 5000 行：可导入 4990 行，错误 10 行');
    expect(notified).toHaveBeenCalledTimes(1);
  });

  it('进行中的任务持续轮询；已通知或很久以前完成的不再弹', async () => {
    server.push(
      task({}),
      task({ id: 2, status: 'DONE', notified: true }),
      task({ id: 3, status: 'FAILED', error: 'x', finishedAt: '2020-01-01T00:00:00' })
    );
    await refreshTasks();
    expect(taskCenter.tasks[0].id).toBe(1);
    server[0] = task({ status: 'FAILED', error: '没有识别到任何列' });
    await wait(1700);
    expect(taskCenter.tasks.find((t) => t.id === 1)!.status).toBe('FAILED');
    await wait(20);
    expect(notices()).toHaveLength(1);
    expect(notices()[0]).toContain('失败');
    expect(notices()[0]).toContain('没有识别到任何列');
  });

  it('本页批量操作：离开后完成弹通知；页面自己展示时不弹', async () => {
    const a = startLocalTask('BATCH', '批量关闭：缺陷', 3);
    a.progress(2, 1);
    expect(taskCenter.tasks[0]).toMatchObject({ title: '批量关闭：缺陷', done: 2, status: 'RUNNING' });
    a.finish({ ok: 2, failed: 1 });
    const b = startLocalTask('BATCH', '批量提交：缺陷', 1);
    b.consume({ ok: 1, failed: 0 });
    await wait(20);
    const batch = notices().filter((n) => n.includes('批量'));
    expect(batch, JSON.stringify(batch)).toHaveLength(1);
    expect(batch[0]).toContain('批量关闭：缺陷 已完成');
    expect(batch[0]).toContain('成功 2 条，失败 1 条');
    expect(notified).not.toHaveBeenCalled();
    expect(taskSummary(taskCenter.tasks.find((t) => t.title === '批量提交：缺陷')!)).toBe('成功 1 条，失败 0 条');
  });
});
