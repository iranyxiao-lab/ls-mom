package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建期命令行：
 * <pre>
 * ls-mom-cli validate &lt;ir.json|yaml&gt;
 * ls-mom-cli generate &lt;ir&gt; --out &lt;目录&gt;
 * ls-mom-cli copilot &lt;需求.txt|.csv|.sql&gt; --out &lt;ir.json&gt; [--catalog &lt;Pack 目录&gt;...] [--replay &lt;录制目录&gt;]
 * ls-mom-cli frappe-import &lt;Frappe 资产目录&gt; --namespace &lt;编码&gt; --out &lt;ir.json&gt; [--license MIT]
 * </pre>
 * 只产出文件（LS-IR、Pack 目录、ChangeSet 制品、报告），没有任何发布或连接生产环境的能力。
 */
public final class LsMomCli {

    private LsMomCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("用法：ls-mom-cli <validate|generate|copilot|frappe-import> ...");
            return 2;
        }
        Args a = Args.parse(Arrays.copyOfRange(args, 1, args.length));
        try {
            return switch (args[0]) {
                case "validate" -> validate(a, out, err);
                case "generate" -> PackGenerator.cli(a, out, err);
                case "copilot" -> BuildCopilot.cli(a, out, err);
                case "frappe-import" -> FrappeImporter.cli(a, out, err);
                default -> {
                    err.println("未知命令：" + args[0]);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
    }

    static int validate(Args a, PrintStream out, PrintStream err) {
        Path file = Path.of(a.positional(0, "LS-IR 文件"));
        JsonNode ir = LsIr.read(file);
        List<LsIr.Problem> problems = LsIr.validate(ir);
        if (problems.isEmpty()) {
            out.println("通过：" + file + "（LS-IR " + ir.path("irVersion").asText() + "，命名空间 " + ir.path("namespace").asText() + "）");
            return 0;
        }
        err.println("校验失败：" + file);
        problems.forEach(p -> err.println("  " + p));
        return 1;
    }

    /** 简单参数：位置参数与 --key value（可重复）。 */
    record Args(List<String> positional, Map<String, List<String>> options) {

        static Args parse(String[] argv) {
            List<String> pos = new ArrayList<>();
            Map<String, List<String>> opts = new LinkedHashMap<>();
            for (int i = 0; i < argv.length; i++) {
                if (argv[i].startsWith("--")) {
                    String k = argv[i].substring(2);
                    String v = i + 1 < argv.length && !argv[i + 1].startsWith("--") ? argv[++i] : "true";
                    opts.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                } else {
                    pos.add(argv[i]);
                }
            }
            return new Args(pos, opts);
        }

        String positional(int i, String what) {
            if (positional.size() <= i) throw new IllegalArgumentException("缺少参数：" + what);
            return positional.get(i);
        }

        String option(String k, String def) {
            List<String> v = options.get(k);
            return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
        }

        String require(String k) {
            String v = option(k, null);
            if (v == null) throw new IllegalArgumentException("缺少参数：--" + k);
            return v;
        }

        List<String> all(String k) {
            return options.getOrDefault(k, List.of());
        }
    }
}
