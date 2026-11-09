import { describe, expect, it } from 'vitest';
import { isExternal, isHttp, isPathMatch } from '@/utils/validate';

describe('utils/validate', () => {
  it('路径通配匹配', () => {
    expect(isPathMatch('/system/**', '/system/user/list')).toBe(true);
    expect(isPathMatch('/system/*', '/system/user/list')).toBe(false);
    expect(isPathMatch('/system/*', '/system/user')).toBe(true);
  });

  it('识别外链', () => {
    expect(isExternal('https://example.com')).toBe(true);
    expect(isExternal('mailto:a@b.c')).toBe(true);
    expect(isExternal('/index')).toBe(false);
    expect(isHttp('http://x')).toBe(true);
  });

  it('运行在真实浏览器中', () => {
    expect(typeof window.document.createElement).toBe('function');
    expect(navigator.userAgent).toMatch(/Chrome/);
  });
});
