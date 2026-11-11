/** 可用性结果导入自测：node --test tools/usability.test.ts */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseUsability, seconds } from './usability.ts';

const md = `### 5.1
| 任务 | 角色 | 参与人数 | 独立完成 | 完成率 | 耗时中位数 | 是否达标 |
|---|---|---|---|---|---|---|
| T1 | 操作工 | 3 | 3 | 100% | 1:35 | 是 |
| T1 | 班组长 |  |  |  |  |  |
| T2 | 审核人 | 4 | 3 | 75% | 50s | 否 |

后文`;

test('耗时写法', () => {
  assert.equal(seconds('1:35'), 95);
  assert.equal(seconds('1分35秒'), 95);
  assert.equal(seconds('2分钟'), 120);
  assert.equal(seconds('50s'), 50);
  assert.equal(seconds(''), undefined);
});

test('解析结果表，跳过未填行', () => {
  assert.deepEqual(parseUsability(md).tasks, [
    { task: 'T1', role: '操作工', participants: 3, completed: 3, medianSec: 95 },
    { task: 'T2', role: '审核人', participants: 4, completed: 3, medianSec: 50 },
  ]);
});

test('独立完成数超过参与人数时报错', () => {
  assert.throws(() => parseUsability(md.replace('| 4 | 3 |', '| 2 | 3 |')), /数字无效/);
});
