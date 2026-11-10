import { afterEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import 'element-plus/dist/index.css';
import { useUndoable } from '@/utils/undoable';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const btn = () => document.querySelector('[data-testid="undo-btn"]') as HTMLElement | null;

afterEach(() => ElMessage.closeAll());

describe('useUndoable', () => {
  it('先乐观更新再执行；撤销只能点一次，调用补偿 Operation 并恢复界面', async () => {
    const state = { shift: 'DAY' };
    const compensate = vi.fn(async () => ({}));
    const after = vi.fn();
    const { run } = useUndoable();
    let seen = '';
    await run({
      done: '已调整到中班',
      apply: () => (state.shift = 'EVENING'),
      revert: () => (state.shift = 'DAY'),
      exec: async () => {
        seen = state.shift;
        return { version: 4, warnings: [], undo: { operation: 'x.move', targetId: '9', input: { shift: 'DAY' } } };
      },
      compensate,
      after
    });
    expect(seen).toBe('EVENING');
    await wait(50);
    btn()!.click();
    btn()?.click();
    await wait(50);
    expect(state.shift).toBe('DAY');
    expect(compensate).toHaveBeenCalledTimes(1);
    expect(compensate).toHaveBeenCalledWith('x.move', '9', 4, { shift: 'DAY' });
    expect(after).toHaveBeenCalledTimes(1);
  });

  it('执行失败时恢复界面并抛出；服务端没有给撤销方式时不显示撤销', async () => {
    const state = { v: 1 };
    const { run } = useUndoable();
    await expect(
      run({
        done: 'x',
        apply: () => (state.v = 2),
        revert: () => (state.v = 1),
        exec: async () => Promise.reject(new Error('冲突')),
        compensate: vi.fn()
      })
    ).rejects.toThrow('冲突');
    expect(state.v).toBe(1);
    await run({ done: '已完成', exec: async () => ({ version: 1, warnings: [] }), compensate: vi.fn() });
    await wait(50);
    const msg = [...document.querySelectorAll('.el-message')].find((m) => m.textContent?.includes('已完成'))!;
    expect(msg).toBeTruthy();
    expect(msg.querySelector('[data-testid="undo-btn"]')).toBeNull();
  });

  it('撤销入口到时自动消失', async () => {
    const { offer } = useUndoable(0.3);
    offer('已隐藏', () => undefined);
    await wait(50);
    expect(btn()).not.toBeNull();
    await wait(900);
    expect(btn()).toBeNull();
  });
});
