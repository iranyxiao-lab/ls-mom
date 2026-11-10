#!/usr/bin/env python3
"""
DOM-05：核心 schema 与核心代码中不得出现培训 / 咨询等扩展领域概念（这些只能由扩展 Pack 提供）。

扫描范围（核心）：ls-mom-modules 下除 ls-mom-biz（标准 Pack）与 ls-mom-architecture-test 外的全部模块的 src/main。
  - Java：去掉注释后检查标识符与字符串字面量；
  - SQL 迁移：去掉注释后检查表名、列名、约束与数据；
  - YAML / JSON 资源：检查键与值。
允许：MetaNames 中用于拦截这些名称的正则本身。

另可加 --db <postgresql://… 或 docker:<容器名>>：检查数据库中非 Pack 台账表（不以 c_ 开头）的表名与列名。

用法：python3 scripts/ci/scan-dom05.py [--db URL] [--self-test]
"""
import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULES = os.path.join(ROOT, "ls-mom-modules")
EXCLUDED_MODULES = {"ls-mom-biz", "ls-mom-architecture-test", "target"}

# 扩展领域概念：英文按标识符拆词（驼峰、下划线、点号）后整词匹配，避免 example、courseOfAction 之外的误伤；中文按词匹配
WORDS = {"training", "trainee", "trainees", "exam", "exams", "examination", "examinee", "course", "courses", "lesson", "lessons",
         "quiz", "quizzes", "consult", "consultation", "consultations", "survey", "surveys", "questionnaire"}
CHINESE = re.compile(r"培训|考试|考题|题库|阅卷|咨询|征求意见|意见征集")
IDENT = re.compile(r"[A-Za-z][A-Za-z0-9]*")


def words(token):
    for part in re.findall(r"[A-Z]+(?=[A-Z][a-z]|\d|\b)|[A-Z]?[a-z]+|[A-Z]+", token):
        yield part.lower()


def find_terms(line):
    out = [m.group(0) for m in CHINESE.finditer(line)]
    for m in IDENT.finditer(line):
        if any(w in WORDS for w in words(m.group(0))):
            out.append(m.group(0))
    return out


ALLOW = [
    # 拦截正则本身
    (re.compile(r"MetaNames\.java$"), re.compile(r"CORE_FORBIDDEN")),
]


def strip_java(src):
    """去掉 Java 注释，保留字符串（字符串中的领域编码同样禁止）。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            # 保留换行以便定位行号
            seg = src[i:(n if j < 0 else j + 2)]
            out.append("\n" * seg.count("\n"))
            i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            seg = src[i:(n if j < 0 else j + 3)]
            out.append(seg)
            i = n if j < 0 else j + 3
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_sql(src):
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return "\n".join(re.sub(r"--.*$", "", line) for line in src.split("\n"))


def strip_yaml(src):
    return "\n".join(re.sub(r"(^|\s)#.*$", "", line) for line in src.split("\n"))


def scan_text(path, text):
    hits = []
    for no, line in enumerate(text.split("\n"), 1):
        for term in find_terms(line):
            if any(p.search(path) and l.search(line) for p, l in ALLOW):
                continue
            hits.append((path, no, term, line.strip()[:160]))
    return hits


def core_files():
    for mod in sorted(os.listdir(MODULES)):
        if mod in EXCLUDED_MODULES or not os.path.isdir(os.path.join(MODULES, mod)):
            continue
        main = os.path.join(MODULES, mod, "src", "main")
        for base, _, files in os.walk(main):
            for f in files:
                yield os.path.join(base, f)


def scan_repo():
    hits, counts = [], {"java": 0, "sql": 0, "res": 0}
    for path in core_files():
        rel = os.path.relpath(path, ROOT)
        if path.endswith(".java"):
            counts["java"] += 1
            hits += scan_text(rel, strip_java(open(path, encoding="utf-8").read()))
        elif path.endswith(".sql"):
            counts["sql"] += 1
            hits += scan_text(rel, strip_sql(open(path, encoding="utf-8").read()))
        elif path.endswith((".yaml", ".yml", ".json")):
            counts["res"] += 1
            text = open(path, encoding="utf-8").read()
            hits += scan_text(rel, strip_yaml(text) if not path.endswith(".json") else text)
    return hits, counts


def scan_db(url):
    q = ("select c.table_name || '.' || c.column_name from information_schema.columns c "
         "where c.table_schema = 'public' and c.table_name not like 'c\\_%' order by 1")
    if url.startswith("docker:"):
        # docker:<容器名>：在容器内用其自身的 POSTGRES_USER / POSTGRES_DB 查询（不在命令行暴露口令）
        cmd = ["docker", "exec", url[7:], "sh", "-c", 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$0"', q]
    else:
        cmd = ["psql", url, "-tAc", q]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.split()
    tables = {x.split(".")[0] for x in out}
    hits = [("db", 0, t, name) for name in sorted(tables | set(out)) for t in find_terms(name.replace(".", " "))]
    return hits, len(tables)


def self_test():
    bad = [
        'jdbc.update("insert into core_training_record values (?)")',
        'String code = "ls.training.Course";',
        "class ExamService {}",
        "boolean consultationOpen;",
        'private static final String T = "培训记录";',
        "create table core_exam_result (id bigint);",
        "Long examId = 1L;",
    ]
    good = [
        "// 例如培训 Pack 提供 qualification",
        "/** 咨询锁 */ long lock;",
        "String example = \"example\";",
        "import java.util.concurrent.Executors;",
        "boolean exempt = examineLater();",
        "-- 培训 Pack 的说明",
    ]
    ok = True
    for s in bad:
        stripped = strip_sql(s) if s.startswith("create") else strip_java(s)
        if not find_terms(stripped):
            print("自检失败：未拦截", s)
            ok = False
    for s in good:
        stripped = strip_sql(s) if s.startswith("--") else strip_java(s)
        if find_terms(stripped):
            print("自检失败：误报", s)
            ok = False
    print("自检通过" if ok else "自检失败")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(0 if self_test() else 1)
    hits, counts = scan_repo()
    print(f"已扫描核心 Java {counts['java']} 个、SQL {counts['sql']} 个、资源 {counts['res']} 个")
    if a.db:
        db_hits, n = scan_db(a.db)
        print(f"已扫描数据库非 Pack 表 {n} 个")
        hits += db_hits
    if hits:
        print("DOM-05 未通过：核心中出现扩展领域概念（应放到扩展 Pack）")
        for path, no, word, line in hits:
            print(f"  {path}:{no}  「{word}」  {line}")
        sys.exit(1)
    print("通过：核心 schema 与核心代码中没有培训 / 咨询等扩展领域概念")


if __name__ == "__main__":
    main()
