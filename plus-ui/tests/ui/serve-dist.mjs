/**
 * 本地预览构建产物：托管 dist（或指定目录），/prod-api 反代到部署环境的后端，用于在不重建镜像的情况下跑页面检查。
 * 用法：node tests/ui/serve-dist.mjs [dist] [端口 18181] [后端 http://127.0.0.1:18081]
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const [dir = 'dist', port = '18181', backend = 'http://127.0.0.1:18081'] = process.argv.slice(2);
const root = path.resolve(dir);
const target = new URL(backend);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json' };

http
  .createServer((req, res) => {
    if (req.url.startsWith('/prod-api/')) {
      const p = http.request({ host: target.hostname, port: target.port, path: req.url, method: req.method, headers: { ...req.headers, host: target.host } }, (r) => {
        res.writeHead(r.statusCode ?? 502, r.headers);
        r.pipe(res);
      });
      p.on('error', () => res.writeHead(502).end());
      return req.pipe(p);
    }
    const url = decodeURIComponent(req.url.split('?')[0]);
    let file = path.join(root, url);
    if (!file.startsWith(root)) return res.writeHead(403).end();
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'index.html');
    const headers = { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream' };
    // 与 nginx gzip_static 一致：有预压缩文件且客户端接受时直接发送，性能测量才贴近部署环境
    if (/\bgzip\b/.test(req.headers['accept-encoding'] ?? '') && fs.existsSync(file + '.gz')) {
      headers['Content-Encoding'] = 'gzip';
      headers.Vary = 'Accept-Encoding';
      file += '.gz';
    }
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  })
  .listen(Number(port), '127.0.0.1', () => console.log(`dist ${root} → http://127.0.0.1:${port}（接口 → ${backend}）`));
