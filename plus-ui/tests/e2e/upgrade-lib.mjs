/** 19.3 / 19.4 升级与恢复验收的共用部分：标准 Pack（访客登记示例改名）与为其追加字段的客户 Pack 的打包。 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-upgrade-'));

function zip(dir, name) {
  const out = path.join(work, name + '.zip');
  execFileSync('python3', [path.join(root, 'scripts/pack-build.py'), dir, out]);
  return out;
}

/** 标准 Pack：1.0.0 为访客登记示例；1.1.0 增加「胸卡号」字段。 */
export function buildStd(ns, version) {
  const tbl = 'c_' + ns.replace(/\./g, '_') + '_visit';
  const dir = path.join(work, `${ns}-${version}`);
  fs.cpSync(path.join(root, 'packs/examples/acme-visitor'), dir, { recursive: true });
  for (const f of fs.readdirSync(dir, { recursive: true })) {
    const p = path.join(dir, f);
    if (!fs.statSync(p).isFile()) continue;
    let s = fs.readFileSync(p, 'utf8').replaceAll('acme.visitor', ns).replaceAll('c_acme_visitor_visit', tbl).replaceAll('idx_acme_visitor_visit_host', `idx_${tbl}_host`);
    if (f === 'pack.yaml') s = s.replace(/^version: .*$/m, `version: ${version}`).replace('name: 访客登记', 'name: 访客登记（标准）');
    if (f === path.join('concepts', 'visit.yaml') && version !== '1.0.0') s += '  - { code: badgeNo, displayName: 胸卡号, dataType: STRING }\n';
    fs.writeFileSync(p, s);
  }
  return zip(dir, `${ns}-${version}`);
}

/** 客户 Pack：为标准访客登记追加「车牌号」「陪同人」两个字段（1.1.0 再加「停车位」），带验收场景。 */
export function buildCust(ns, std, version) {
  const dir = path.join(work, `${ns}-${version}`);
  fs.mkdirSync(path.join(dir, 'concepts'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'tests/scenarios'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'pack.yaml'), `code: ${ns}
version: ${version}
name: 客户访客扩展
owner: { name: 客户行政部 }
requester: { name: 安保队长 }
reviewDue: 2099-01-01
dependsOn:
  - { code: ${std}, version: ">=1.0.0 <2.0.0" }
`);
  fs.writeFileSync(path.join(dir, 'concepts/visit-ext.yaml'), `extends: ${std}.Visit
fields:
  - { code: plateNo, displayName: 车牌号, dataType: STRING }
  - { code: escort, displayName: 陪同人, dataType: STRING }
${version === '1.0.0' ? '' : '  - { code: parkingLot, displayName: 停车位, dataType: STRING }\n'}`);
  fs.writeFileSync(path.join(dir, 'tests/scenarios/plate.yaml'), `name: 登记带车牌的访客
steps:
  - create: { concept: ${std}.Visit, as: v, values: { visitorName: 验收访客, hostName: 李工, visitDate: "2026-11-10", plateNo: 沪A12345, escort: 王保安 } }
  - expect: { concept: ${std}.Visit, target: v, values: { plateNo: 沪A12345, escort: 王保安 } }
`);
  return zip(dir, `${ns}-${version}`);
}

export const b64 = (file) => fs.readFileSync(file).toString('base64');
