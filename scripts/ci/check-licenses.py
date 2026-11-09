#!/usr/bin/env python3
"""依赖许可证门禁：读取 CycloneDX SBOM，拒绝 GPL／AGPL 链接依赖。

规则：
- 一个组件列出多个许可证视为可选（双许可），只要有一个不在禁止范围即通过；
- GPL／AGPL（含 "GNU General Public License"、"Affero"）禁止；LGPL 允许；
  带 Classpath Exception 的 GPL 允许（JDK／Jakarta 惯例）；
- 第三方组件缺少许可证信息视为失败，需人工核实后写入允许清单；
- 本项目自身模块（com.lsmom、org.dromara）跳过。

用法：check-licenses.py <bom.json> [<bom.json> ...]
允许清单：scripts/ci/license-allowlist.txt，每行 `组件坐标  理由`（坐标为 purl 去掉版本或 group:name）。
"""
import json
import re
import sys
from pathlib import Path

OWN_GROUPS = {"com.lsmom", "org.dromara"}
DENY = re.compile(r"(\bA?GPL\b|AGPL|GNU (AFFERO )?GENERAL PUBLIC|AFFERO)", re.I)
LGPL = re.compile(r"(LGPL|LESSER GENERAL PUBLIC|LIBRARY GENERAL PUBLIC)", re.I)
CLASSPATH_EXCEPTION = re.compile(r"classpath[ -]exception", re.I)


def load_allowlist():
    path = Path(__file__).with_name("license-allowlist.txt")
    if not path.exists():
        return set()
    keys = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            keys.add(line.split()[0])
    return keys


def license_names(component):
    names = []
    for entry in component.get("licenses") or []:
        if "expression" in entry:
            names.extend(p.strip() for p in re.split(r"\s+OR\s+", entry["expression"]))
        else:
            lic = entry.get("license", {})
            names.append(lic.get("id") or lic.get("name") or "")
    return [n for n in names if n]


def denied(name):
    if CLASSPATH_EXCEPTION.search(name) or LGPL.search(name):
        return False
    return bool(DENY.search(name))


def keys_of(component):
    group, name = component.get("group", ""), component.get("name", "")
    purl = re.sub(r"@[^@?#]*$", "", component.get("purl", "").split("?")[0])
    return {f"{group}:{name}" if group else name, purl}


def main(paths):
    allow = load_allowlist()
    problems, total = [], 0
    for path in paths:
        bom = json.loads(Path(path).read_text(encoding="utf-8"))
        for comp in bom.get("components", []):
            if comp.get("group") in OWN_GROUPS:
                continue
            total += 1
            coord = f"{comp.get('group', '')}:{comp.get('name')}@{comp.get('version')}".lstrip(":")
            if keys_of(comp) & allow:
                continue
            names = license_names(comp)
            if not names:
                problems.append(f"{coord}  缺少许可证信息（核实后写入 license-allowlist.txt）")
            elif all(denied(n) for n in names):
                problems.append(f"{coord}  禁止的许可证：{' | '.join(names)}")
    if problems:
        print(f"许可证扫描失败（{len(problems)} 个依赖不合规）：")
        for p in sorted(set(problems)):
            print("  - " + p)
        return 1
    print(f"许可证扫描通过：检查 {total} 个第三方组件，未发现 GPL／AGPL 链接依赖。")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
