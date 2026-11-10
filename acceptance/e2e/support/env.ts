/** 前端构建配置中的客户端编号与接口前缀（与 plus-ui/.env.production 一致）。 */
import fs from 'node:fs';
import path from 'node:path';
import { ROOT } from '../../tools/lib.ts';

const env = fs.readFileSync(path.join(ROOT, 'plus-ui/.env.production'), 'utf8');
export const CLIENT_ID = /^VITE_APP_CLIENT_ID\s*=\s*'([^']*)'/m.exec(env)![1];
export const API_PREFIX = /^VITE_APP_BASE_API\s*=\s*'([^']*)'/m.exec(env)![1];
