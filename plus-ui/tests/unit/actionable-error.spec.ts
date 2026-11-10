import { afterEach, describe, expect, it, vi } from 'vitest';
import { ElNotification } from 'element-plus';
import 'element-plus/dist/index.css';
import { registerFixAction, setFixNavigator, showActionableError } from '@/utils/actionableError';

const wait = (ms = 50) => new Promise((r) => setTimeout(r, ms));
const last = () => [...document.querySelectorAll('[data-testid="error-notice"]')].at(-1) as HTMLElement;

afterEach(() => ElNotification.closeAll());

const expired = {
  message: '李四的动火作业资质已于 10 月 3 日过期',
  errorCode: 'GUARD_FAILED',
  trace: 'abc123',
  details: {
    reason: 'QUALIFICATION_EXPIRED',
    guard: 'ls.training.assigneeQualified',
    partyId: 7,
    why: '「动火：焊补管道」属于动火作业，承担人必须持有有效的培训记录',
    fix: [
      { label: '改派他人', action: 'reassign', partyId: 7 },
      { label: '查看培训记录', to: '/lsmom/ledger/ls.training.TrainingRecord?id=5' }
    ]
  }
};

describe('可操作的错误提示', () => {
  it('说明原因与解决办法；修复入口交给页面或跳转；技术详情折叠并附追踪编号', async () => {
    const reassign = vi.fn();
    const nav = vi.fn();
    setFixNavigator(nav);
    const off = registerFixAction('reassign', reassign);
    showActionableError(expired);
    await wait();
    const n = last().closest('.el-notification')!;
    expect(n.textContent).toContain('李四的动火作业资质已于 10 月 3 日过期');
    expect(n.querySelector('[data-testid="error-why"]')!.textContent).toContain('属于动火作业');
    const tech = n.querySelector('[data-testid="error-tech"]') as HTMLDetailsElement;
    expect(tech.open).toBe(false);
    expect(tech.textContent).toContain('追踪编号：abc123');
    expect(tech.textContent).toContain('QUALIFICATION_EXPIRED');
    expect(n.textContent).not.toMatch(/Exception|at com\./);
    (n.querySelector('[data-testid="error-fix-reassign"]') as HTMLElement).click();
    await wait();
    expect(reassign).toHaveBeenCalledWith(expect.objectContaining({ partyId: 7 }), expect.objectContaining({ errorCode: 'GUARD_FAILED' }));

    showActionableError(expired);
    await wait();
    (last().querySelector('[data-testid="error-fix-link"]') as HTMLElement).click();
    expect(nav).toHaveBeenCalledWith('/lsmom/ledger/ls.training.TrainingRecord?id=5');
    off();
  });

  it('当前页面没有处理的修复动作不显示按钮；常见错误编码给出通用说明', async () => {
    showActionableError(expired);
    await wait();
    expect(last().querySelector('[data-testid="error-fix-reassign"]')).toBeNull();
    expect(last().querySelector('[data-testid="error-fix-link"]')).not.toBeNull();

    showActionableError({ message: '对象已被他人修改，请刷新后重试', errorCode: 'VERSION_CONFLICT', details: {} });
    await wait();
    expect(last().querySelector('[data-testid="error-why"]')!.textContent).toContain('别人修改了这条记录');
    expect(last().querySelector('[data-testid="error-fix-reload"]')!.textContent).toBe('刷新后重试');
  });
});
