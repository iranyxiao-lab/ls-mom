/**
 * 旅程用的业务调用助手：按角色会话执行 Operation、读记录、断言被拒并留审计。
 * 版本号默认按通用台账读取（GET /api/crud/<concept>/<id>）；没有台账读权限的角色由调用方传入版本。
 */
import { expect, type Session } from './walk.ts';

export const ok = <T = any>(r: { body: any }, what: string): T => {
  expect(r.body.code, `${what}：${r.body.msg}`).toBe(200);
  return r.body.data as T;
};

export const conceptOf = (code: string) => code.slice(0, code.lastIndexOf('.'));

export async function get(s: Session, concept: string, id: string | number) {
  return ok(await s.api('GET', `/api/crud/${concept}/${id}`), `读取 ${concept}#${id}`);
}

export async function create(s: Session, concept: string, body: Record<string, unknown>) {
  return ok(await s.api('POST', `/api/crud/${concept}`, body), `新建 ${concept}`);
}

export async function query(s: Session, concept: string, filters: unknown[] = [], size = 20) {
  return ok(await s.api('POST', `/api/crud/${concept}/query`, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size }), `查询 ${concept}`).rows as any[];
}

/** 执行 Operation；返回 { data, traceId } */
export async function op(s: Session, code: string, id: string | number, input: unknown = {}, version?: number, key?: string) {
  const v = version ?? (await get(s, conceptOf(code), id)).version;
  const r = await s.api('POST', `/api/op/${code}`, { targetId: String(id), version: v, input, ...(key ? { idempotencyKey: key } : {}) });
  return { data: ok(r, code), traceId: r.traceId, raw: r };
}

/** 调用被拒：业务码不是 200，可选匹配错误码或提示 */
export async function denied(r: Promise<{ status: number; body: any }> | { status: number; body: any }, what: string, match?: RegExp) {
  const x = await r;
  expect(x.body?.code, `${what} 应被拒绝`).not.toBe(200);
  if (match) expect(`${x.body?.errorCode ?? ''} ${x.body?.msg ?? ''}`, what).toMatch(match);
  return x;
}

/** 对象的操作审计（含拒绝）：找到 opCode 匹配且结果不是成功的一条 */
export async function auditedDenial(admin: Session, concept: string, id: string | number, opCode: string | RegExp) {
  const list = ok<any>(await admin.api('POST', '/api/q/ls.audit.objectOperations', { concept, id: String(id) }), '查询对象审计').rows as any[];
  const match = (c: string) => (typeof opCode === 'string' ? c === opCode : opCode.test(c));
  const hit = list.find((a) => match(String(a.opCode)) && String(a.result) !== 'SUCCESS');
  expect(hit, `审计中应有 ${opCode} 的拒绝记录（现有：${list.map((a) => `${a.opCode}/${a.result}`).join('，')}）`).toBeTruthy();
  return hit;
}

/** 轮询直到条件满足（异步投递、读模型） */
export async function until<T>(fn: () => Promise<T | undefined | null | false>, what: string, ms = 15_000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v as T;
    if (Date.now() > end) throw new Error(`等待超时：${what}`);
    await new Promise((r) => setTimeout(r, 300));
  }
}
