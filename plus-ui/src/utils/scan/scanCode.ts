/**
 * 扫码：二维码内容取出对象编码；扫码枪（键盘输入设备）识别——字符在极短间隔内连续输入并以 Enter 结束。
 */

/** 二维码可能是链接（…/asset/P-101、…?code=P-101）或纯编码，统一取出编码。 */
export function normalizeCode(text: string): string {
  const s = (text ?? '').trim();
  if (!s) return '';
  try {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
      const u = new URL(s);
      const q = u.searchParams.get('code') ?? u.searchParams.get('c');
      if (q) return q.trim();
      const parts = u.pathname.split('/').filter(Boolean);
      if (parts.length) return decodeURIComponent(parts[parts.length - 1]);
      return u.hostname;
    }
  } catch {
    /* 不是合法链接，按原文处理 */
  }
  return s;
}

export interface WedgeOptions {
  /** 至少几个字符才算扫码 */
  minLength?: number;
  /** 相邻按键最大间隔（毫秒），人手输入通常大于 80ms */
  maxInterval?: number;
}

/**
 * 扫码枪识别：把 keydown 依次交给 feed()；以 Enter 结束且每个字符间隔都很短时返回编码，否则返回 null（按普通输入处理）。
 */
export function createWedgeDetector(opts: WedgeOptions = {}) {
  const min = opts.minLength ?? 4;
  const gap = opts.maxInterval ?? 50;
  let buf = '';
  let last = 0;
  let fast = true;
  const reset = () => {
    buf = '';
    fast = true;
    last = 0;
  };
  return {
    /** now 默认取按键事件的产生时间（页面繁忙时事件排队处理，用处理时间会把扫码枪误判为人手输入） */
    feed(
      e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey'> & { timeStamp?: number },
      now = e.timeStamp || performance.now()
    ): string | null {
      if (e.ctrlKey || e.metaKey || e.altKey) {
        reset();
        return null;
      }
      if (e.key === 'Enter') {
        // 扫码枪在最后一个字符后立即发 Enter；停顿后才按的 Enter 按人手输入处理
        const code = buf.length >= min && fast && now - last <= gap * 4 ? buf : null;
        reset();
        return code;
      }
      if (e.key.length !== 1) return null;
      if (last && now - last > gap) {
        // 间隔太长：重新开始计（前面是人手输入）
        buf = '';
        fast = true;
      }
      buf += e.key;
      last = now;
      return null;
    },
    /** 当前输入看起来像扫码枪（已有足够多的快速字符），用于让外层的 Enter 跳字段先让行 */
    pending() {
      return buf.length >= min && fast && !!last;
    },
    reset
  };
}
