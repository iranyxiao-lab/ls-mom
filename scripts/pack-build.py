#!/usr/bin/env python3
"""打包 Pack 目录为 zip（含 MANIFEST.sha256 清单）。

用法：python3 scripts/pack-build.py <Pack 目录> [输出.zip]
    默认输出 <目录名>-<版本>.zip。文件按路径排序、时间戳固定，同样内容得到同样的 zip。
    打包前做最基本的检查（pack.yaml 存在、负责人与提出人已填）；完整校验在提交 ChangeSet 时由平台执行。
"""
import hashlib
import os
import re
import sys
import zipfile

SKIP_DIRS = {'.git', 'node_modules', '__pycache__'}


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    src = os.path.abspath(sys.argv[1])
    manifest = os.path.join(src, 'pack.yaml')
    if not os.path.isfile(manifest):
        sys.exit(f'缺少 {manifest}')
    text = open(manifest, encoding='utf-8').read()
    version = re.search(r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$', text, re.M)
    if not version:
        sys.exit('pack.yaml 缺少 version（主.次.修订）')
    for key, label in (('owner', '业务负责人'), ('requester', '需求提出人')):
        if not re.search(rf'^{key}:\s*\S', text, re.M):
            sys.exit(f'pack.yaml 必须填写 {key}（{label}）')
    out = sys.argv[2] if len(sys.argv) > 2 else f'{os.path.basename(src)}-{version.group(1)}.zip'

    files = []
    for d, dirs, names in os.walk(src):
        dirs[:] = sorted(x for x in dirs if x not in SKIP_DIRS and not x.startswith('.'))
        for n in names:
            if n.startswith('.') or n == 'MANIFEST.sha256':
                continue
            p = os.path.join(d, n)
            files.append((os.path.relpath(p, src).replace(os.sep, '/'), p))
    files.sort()
    sums = []
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for rel, p in files:
            data = open(p, 'rb').read()
            sums.append(f'{hashlib.sha256(data).hexdigest()}  {rel}\n')
            z.writestr(zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0)), data)
        z.writestr(zipfile.ZipInfo('MANIFEST.sha256', date_time=(1980, 1, 1, 0, 0, 0)), ''.join(sums))
    print(f'{out}（{len(files)} 个文件）')


if __name__ == '__main__':
    main()
