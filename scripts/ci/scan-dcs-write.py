#!/usr/bin/env python3
"""DCS 写入能力黑名单扫描（CI 门禁）。

扫描范围：
- 代码定义的 Operation：Java 源码中的 @LsOperation(code=…, name=…, concept=…)；
- Pack 定义的 Operation：lsmom-packs/**/operations/*.yaml 与 packs/**/operations/*.yaml 的 operation / name；
- Agent / MCP 工具：ls-mom-mcp 源码中声明的工具（@Tool / @McpTool / tool(…) 名称与说明）；
- DCS 模块的 HTTP 接口：com.lsmom.dcs 下除允许清单外不得有写方法映射；
- 已发布清单（可选）：--catalog <json>，为 /api/op/catalog 的导出（含 Agent 可见工具），用于联调环境复核。

规则见 dcs-write-denylist.txt：名称 / 编码同时命中「控制系统对象」与「写入动作」，或 Operation 落在 ls.dcs.* 概念下，即判为写入能力。
用法：scan-dcs-write.py [--root 仓库根] [--catalog catalog.json] [--self-test]
"""
import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# DCS 模块中允许的写方法：只改业务库（测点语义、报警处置），不触达 DCS
DCS_HTTP_ALLOW = {"PUT /points", "POST /alarms/{id}/link", "POST /alarms/{id}/draft", "POST /alarms/{id}/ignore"}


def load_rules():
    rules = {"target": [], "verb": [], "namespace": []}
    for line in (HERE / "dcs-write-denylist.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        kind, pattern = line.split(None, 1)
        rules[kind].append(re.compile(pattern, re.I))
    return rules


def is_write(rules, code, name, concept=""):
    text = f"{code} {name}"
    if any(p.search(concept or "") or p.search(code or "") for p in rules["namespace"]):
        return "落在 DCS 概念命名空间"
    if any(p.search(text) for p in rules["target"]) and any(p.search(text) for p in rules["verb"]):
        return "同时命中控制系统对象与写入动作"
    return None


ANNOTATION = re.compile(r"@LsOperation\((.*?)\)\s*(?:public|@)", re.S)
ATTR = lambda k: re.compile(k + r'\s*=\s*"([^"]*)"')
TOOL = re.compile(r'@(?:Mcp)?Tool\s*\(([^)]*)\)|\btool\(\s*"([^"]+)"\s*,\s*"([^"]*)"', re.S)
MAPPING = re.compile(r"@(Post|Put|Delete|Patch)Mapping\(\s*\"([^\"]*)\"")


def java_operations(root):
    for f in root.rglob("src/main/java/**/*.java"):
        if "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8")
        for m in ANNOTATION.finditer(src):
            body = m.group(1)
            get = lambda k: (ATTR(k).search(body) or [None, ""])[1]
            yield f, get("code"), get("name"), get("concept")


def yaml_operations(root):
    import yaml
    files = list(root.rglob("lsmom-packs/**/operations/*.yaml")) + list(root.glob("packs/**/operations/*.yaml"))
    for f in files:
        if "/target/" in str(f) or "node_modules" in str(f):
            continue
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        concept = doc.get("concept", "") if isinstance(doc, dict) else ""

        def walk(node):
            if isinstance(node, dict):
                if isinstance(node.get("operation"), str):
                    yield node["operation"], str(node.get("name", ""))
                for v in node.values():
                    yield from walk(v)
            elif isinstance(node, list):
                for v in node:
                    yield from walk(v)

        for code, name in walk(doc):
            yield f, code, name, concept


OP_CODE = re.compile(r'"((?:ls|acme)\.[A-Za-z]+\.[A-Za-z]+\.[A-Za-z]+)"')


def java_machine_operations(root):
    """代码定义的状态机迁移（如 ls.core.Issue.submit）：取编码与所在行文本。"""
    for f in root.rglob("src/main/java/**/*.java"):
        if "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8")
        if "StateMachineDefinition" not in src:
            continue
        prefixes = dict(re.findall(r'String\s+(\w+)\s*=\s*"([\w.]+\.)"', src))
        for line in src.splitlines():
            for m in OP_CODE.finditer(line):
                code = m.group(1)
                yield f, code, line.strip(), ".".join(code.split(".")[:3])
            # 迁移写法：t(前缀变量 + "submit", "提交", …)
            for var, op, name in re.findall(r'\bt\(\s*(\w+)\s*\+\s*"(\w+)"\s*,\s*"([^"]*)"', line):
                if var in prefixes:
                    code = prefixes[var] + op
                    yield f, code, name, ".".join(code.split(".")[:3])


def mcp_tools(root):
    for f in root.rglob("ls-mom-mcp/src/main/**/*"):
        if not f.is_file() or "/target/" in str(f) or f.suffix not in {".java", ".yaml", ".yml", ".json"}:
            continue
        src = f.read_text(encoding="utf-8")
        for m in TOOL.finditer(src):
            if m.group(1) is not None:
                body = m.group(1)
                name = (ATTR("name").search(body) or [None, ""])[1]
                desc = (ATTR("description").search(body) or [None, ""])[1]
                yield f, name, desc
            else:
                yield f, m.group(2), m.group(3)


def dcs_http(root):
    for f in root.rglob("ls-mom-dcs/src/main/java/**/*.java"):
        for m in MAPPING.finditer(f.read_text(encoding="utf-8")):
            yield f, f"{m.group(1).upper()} {m.group(2)}"


def scan(root, catalog=None):
    rules = load_rules()
    problems = []
    count = 0
    for f, code, name, concept in list(java_operations(root)) + list(java_machine_operations(root)) + list(yaml_operations(root)):
        count += 1
        why = is_write(rules, code, name, concept)
        if why:
            problems.append(f"Operation {code}（{name}）{why}：{f.relative_to(root)}")
    tools = 0
    for f, name, desc in mcp_tools(root):
        tools += 1
        why = is_write(rules, name, desc)
        if why:
            problems.append(f"MCP 工具 {name}（{desc}）{why}：{f.relative_to(root)}")
    for f, mapping in dcs_http(root):
        if mapping not in DCS_HTTP_ALLOW:
            problems.append(f"DCS 模块接口 {mapping} 不在只读允许清单：{f.relative_to(root)}")
    published = 0
    if catalog:
        items = json.loads(Path(catalog).read_text(encoding="utf-8"))
        items = items.get("data", items) if isinstance(items, dict) else items
        for it in items:
            published += 1
            why = is_write(rules, it.get("code", ""), it.get("name", ""), it.get("concept", ""))
            if why:
                problems.append(f"已发布 {it.get('code')}（{it.get('name')}，暴露 {it.get('exposure')}）{why}")
    return count, tools, published, problems


def self_test():
    rules = load_rules()
    bad = [("ls.dcs.Point.write", "写入测点", "ls.dcs.Point"), ("ls.ops.dcsSetpoint", "下发 DCS 设定值", "ls.ops.X"),
           ("plant.interlock.bypass", "解除联锁", "ls.ops.X"), ("opcWriteTag", "OPC write", "")]
    good = [("ls.asset.Asset.stop", "停机", "ls.asset.Asset"), ("ls.core.Issue.submit", "提交", "ls.core.Issue"),
            ("ls.production.Handover.publish", "发布交接班（含 DCS 摘要）", "ls.production.Handover")]
    for c, n, k in bad:
        assert is_write(rules, c, n, k), f"应拦截：{c}"
    for c, n, k in good:
        assert not is_write(rules, c, n, k), f"误报：{c}"
    print("自检通过：黑名单可识别写入类能力，且不误报业务操作")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(HERE.parent.parent))
    ap.add_argument("--catalog")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    self_test()
    if a.self_test:
        return 0
    root = Path(a.root).resolve()
    count, tools, published, problems = scan(root, a.catalog)
    if count == 0:
        print("没有扫描到任何 Operation，扫描范围可能有误", file=sys.stderr)
        return 2
    print(f"已扫描 Operation {count} 个、MCP 工具 {tools} 个" + (f"、已发布清单 {published} 项" if a.catalog else ""))
    if problems:
        print("发现 DCS 写入类能力（系统只读 DCS，必须移除）：", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1
    print("通过：不存在任何 DCS 写入类能力")
    return 0


if __name__ == "__main__":
    sys.exit(main())
