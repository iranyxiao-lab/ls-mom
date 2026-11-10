#!/usr/bin/env python3
"""Agent 永不暴露清单扫描（CI 门禁）。

1. 命中 agent-denylist.txt 的 Operation 不得声明 DENY / NONE 以外的暴露等级。扫描：
   - 代码 Operation：Java 源码中的 @LsOperation(code=…, exposure=…, permission=…, guards=…)（支持同文件或 类名.常量 的字符串常量）；
   - Pack 状态迁移：lsmom-packs/**/operations/*.yaml 中的 operation / exposure / permission / guards（未声明暴露等级为 NONE）；
   - 核心状态机：CoreStateMachines 的 t(前缀 + "迁移名", …)，暴露等级按其 switch 规则推导。
2. docs/agent-boundary.md 中 <!-- deny-list:start --> 与 <!-- deny-list:end --> 之间的表格须与 agent-denylist.txt 完全一致。

匹配规则与 AgentPolicy 相同：编码通配（* 匹配任意字符）、guard:<守卫编码>、perm:<权限点通配>。
用法：scan-agent-exposure.py [--root 仓库根] [--self-test]
"""
import argparse
import re
import sys
from pathlib import Path

DENYLIST = Path("ls-mom-modules/ls-mom-action/src/main/resources/agent-denylist.txt")
DOC = Path("docs/agent-boundary.md")
ALLOWED = {"DENY", "NONE"}

ANNOTATION = re.compile(r"@LsOperation\((.*?)\)\s*(?:public|@|static|final|class)", re.S)
STR_CONST = re.compile(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;')
CLASS_NAME = re.compile(r"\b(?:class|interface|record|enum)\s+(\w+)")


def parse_denylist(text):
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            raise ValueError("清单格式错误：" + line)
        out.append((parts[0], parts[1]))
    return out


def parse_doc(text):
    m = re.search(r"<!-- deny-list:start -->(.*?)<!-- deny-list:end -->", text, re.S)
    if not m:
        raise ValueError("docs/agent-boundary.md 缺少 deny-list 标记")
    out = []
    for line in m.group(1).splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or cells[0] in ("类别", "") or set(cells[0]) <= {"-", " "}:
            continue
        out.append((cells[0], cells[1].strip("`")))
    return out


def glob(p):
    return re.compile("^" + ".*".join(re.escape(x) for x in p.split("*")) + "$")


def hit(entries, code, permission, guards):
    for cat, pat in entries:
        if pat.startswith("guard:"):
            if pat[6:] in guards:
                return cat, pat
        elif pat.startswith("perm:"):
            if permission and glob(pat[5:]).match(permission):
                return cat, pat
        elif code and glob(pat).match(code):
            return cat, pat
    return None


def constants(files):
    """全部字符串常量：NAME → 值（同名冲突时按 类名.NAME 区分）。"""
    by_class, by_name = {}, {}
    for f, src in files:
        cls = (CLASS_NAME.search(src) or [None, f.stem])[1]
        for name, value in STR_CONST.findall(src):
            by_class[cls + "." + name] = value
            by_name.setdefault(name, value)
    return by_class, by_name


def resolve(expr, by_class, by_name):
    expr = expr.strip()
    if expr.startswith('"') and expr.endswith('"'):
        return expr[1:-1]
    if "+" in expr:
        return "".join(resolve(p, by_class, by_name) or "" for p in expr.split("+"))
    if expr in by_class:
        return by_class[expr]
    return by_name.get(expr.split(".")[-1])


def attr(body, key):
    m = re.search(r"\b" + key + r"\s*=\s*(\"[^\"]*\"|[\w.]+(?:\s*\+\s*(?:\"[^\"]*\"|[\w.]+))*)", body)
    return m.group(1) if m else None


def java_operations(root):
    files = [(f, f.read_text(encoding="utf-8")) for f in root.rglob("src/main/java/**/*.java") if "/target/" not in str(f)]
    by_class, by_name = constants(files)
    for f, src in files:
        for m in ANNOTATION.finditer(src):
            body = m.group(1)
            code = resolve(attr(body, "code") or '""', by_class, by_name)
            exp = attr(body, "exposure")
            exposure = exp.split(".")[-1] if exp else "NONE"
            perm = resolve(attr(body, "permission") or '""', by_class, by_name)
            g = re.search(r"\bguards\s*=\s*\{([^}]*)\}", body)
            guards = [resolve(x, by_class, by_name) for x in g.group(1).split(",") if x.strip()] if g else []
            yield str(f.relative_to(root)), code, exposure, perm, guards


def yaml_operations(root):
    import yaml
    for f in root.rglob("lsmom-packs/**/operations/*.yaml"):
        if "/target/" in str(f):
            continue
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        found = []

        def walk(n):
            if isinstance(n, dict):
                if "operation" in n and isinstance(n["operation"], str):
                    found.append(n)
                for v in n.values():
                    walk(v)
            elif isinstance(n, list):
                for v in n:
                    walk(v)

        walk(doc)
        for n in found:
            yield (str(f.relative_to(root)), n["operation"], str(n.get("exposure") or "NONE"), n.get("permission") or "",
                   [str(x) for x in (n.get("guards") or [])])


def core_machine_operations(root):
    for f in root.rglob("src/main/java/**/CoreStateMachines.java"):
        if "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8")
        rules, default = {}, "NONE"
        sw = re.search(r"switch\s*\(\s*last\s*\)\s*\{(.*?)\};", src, re.S)
        if sw:
            for cases, value in re.findall(r"case\s+([^-]+?)\s*->\s*\"(\w+)\"", sw.group(1)):
                for c in re.findall(r'"(\w+)"', cases):
                    rules[c] = value
            d = re.search(r"default\s*->\s*\"(\w+)\"", sw.group(1))
            default = d.group(1) if d else default
        prefixes = dict(re.findall(r'String\s+(\w+)\s*=\s*"(ls\.[^"]+\.)"\s*;', src))
        for var, name, perm in re.findall(r't\(\s*(\w+)\s*\+\s*"(\w+)"\s*,\s*"[^"]*"\s*,\s*(?:List\.of\([^)]*\)|\w+)\s*,\s*"[^"]*"\s*,\s*"([^"]*)"', src):
            if var in prefixes:
                yield str(f.relative_to(root)), prefixes[var] + name, rules.get(name, default), perm, []


def scan(root):
    problems = []
    entries = parse_denylist((root / DENYLIST).read_text(encoding="utf-8"))
    doc = parse_doc((root / DOC).read_text(encoding="utf-8"))
    missing = [e for e in entries if e not in doc]
    extra = [e for e in doc if e not in entries]
    for c, p in missing:
        problems.append(f"{DOC} 缺少清单条目：{c} {p}")
    for c, p in extra:
        problems.append(f"{DOC} 多出清单中没有的条目：{c} {p}")
    ops = list(java_operations(root)) + list(yaml_operations(root)) + list(core_machine_operations(root))
    checked = 0
    for where, code, exposure, perm, guards in ops:
        if not code:
            continue
        h = hit(entries, code, perm, guards)
        if not h:
            continue
        checked += 1
        if exposure not in ALLOWED:
            problems.append(f"{where}: {code} 命中永不暴露清单（{h[0]} {h[1]}），暴露等级却为 {exposure}（须为 DENY 或 NONE）")
    return problems, len(ops), checked, len(entries)


def self_test():
    entries = parse_denylist("APPROVAL *.approve\nDUAL_SIGN guard:ls.std.dualSign.exclusive\nGENERIC_DELETE perm:crud:*:remove\n")
    assert hit(entries, "acme.x.Order.approve", "", []) == ("APPROVAL", "*.approve")
    assert hit(entries, "acme.x.Order.approveDraft", "", []) is None
    assert hit(entries, "a.b.C.sign", "", ["ls.std.dualSign.exclusive"])[0] == "DUAL_SIGN"
    assert hit(entries, "a.b.C.x", "crud:a.b.C:remove", [])[0] == "GENERIC_DELETE"
    doc = parse_doc("x\n<!-- deny-list:start -->\n| 类别 | 匹配 | 说明 |\n| --- | --- | --- |\n| APPROVAL | `*.approve` | a |\n<!-- deny-list:end -->")
    assert doc == [("APPROVAL", "*.approve")], doc
    bc, bn = constants([(Path("P.java"), 'class P { static final String CODE = "ls.p"; static final String OP = "ls.p.X.approve"; }')])
    assert resolve("P.OP", bc, bn) == "ls.p.X.approve" and resolve('CODE + ".X"', bc, bn) == "ls.p.X"
    print("自检通过")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        self_test()
        return 0
    problems, total, checked, n = scan(Path(a.root))
    print(f"已扫描 Operation {total} 个，命中永不暴露清单 {checked} 个；清单 {n} 条")
    for p in problems:
        print("✗ " + p)
    if problems:
        return 1
    print("通过：命中清单的 Operation 均为 DENY / NONE，文档清单与 agent-denylist.txt 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
