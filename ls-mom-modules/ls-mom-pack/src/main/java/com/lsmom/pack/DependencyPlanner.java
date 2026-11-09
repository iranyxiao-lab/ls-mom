package com.lsmom.pack;

import com.lsmom.core.error.LsBizException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 依赖分析：依赖缺失、版本范围不满足、循环依赖都拒绝；通过时按拓扑顺序返回待安装的 Pack（依赖在前）。
 * 升级某个 Pack 时，已安装 Pack 对它的版本要求也必须仍然满足。
 */
public final class DependencyPlanner {

    /** 已安装 Pack 的依赖信息。 */
    public record Installed(String code, String version, String status, List<PackManifest.Dependency> dependsOn) {
    }

    public record Problem(String code, String pack, String message) {
    }

    private DependencyPlanner() {
    }

    public static List<PackManifest> plan(Collection<PackManifest> candidates, Collection<Installed> installed) {
        Map<String, PackManifest> cand = new TreeMap<>();
        for (PackManifest m : candidates) {
            if (cand.put(m.code(), m) != null) throw new LsBizException("PACK_DUPLICATE", "同一批次中 Pack 重复：" + m.code());
        }
        Map<String, Installed> inst = new HashMap<>();
        installed.forEach(i -> inst.put(i.code(), i));

        // 依赖图：候选覆盖已安装的同编码 Pack
        Map<String, List<String>> graph = new TreeMap<>();
        inst.values().stream().filter(i -> !"UNINSTALLED".equals(i.status()))
            .forEach(i -> graph.put(i.code(), i.dependsOn().stream().map(PackManifest.Dependency::code).toList()));
        cand.values().forEach(m -> graph.put(m.code(), m.dependsOn().stream().map(PackManifest.Dependency::code).toList()));
        List<String> cycle = findCycle(graph);
        if (!cycle.isEmpty()) {
            String path = String.join(" → ", cycle);
            throw new LsBizException("PACK_DEPENDENCY_CYCLE", "存在循环依赖：" + path, Map.of("cycle", cycle));
        }

        List<Problem> problems = new ArrayList<>();
        for (PackManifest m : cand.values()) {
            for (PackManifest.Dependency d : m.dependsOn()) {
                String version;
                if (cand.containsKey(d.code())) {
                    version = cand.get(d.code()).version();
                } else if (inst.containsKey(d.code()) && "ENABLED".equals(inst.get(d.code()).status())) {
                    version = inst.get(d.code()).version();
                } else if (inst.containsKey(d.code()) && "DISABLED".equals(inst.get(d.code()).status())) {
                    problems.add(new Problem("PACK_DEPENDENCY_DISABLED", m.code(), m.code() + " 依赖的 " + d.code() + " 已停用"));
                    continue;
                } else {
                    problems.add(new Problem("PACK_DEPENDENCY_MISSING", m.code(), m.code() + " 依赖的 " + d.code() + " " + d.version() + " 未安装"));
                    continue;
                }
                if (!SemVer.range(d.version()).test(SemVer.parse(version))) {
                    problems.add(new Problem("PACK_DEPENDENCY_VERSION", m.code(),
                        m.code() + " 要求 " + d.code() + " " + d.version() + "，可用版本为 " + version));
                }
            }
        }
        // 升级：其他已安装 Pack 对被升级 Pack 的版本要求
        for (Installed i : inst.values()) {
            if (cand.containsKey(i.code()) || !"ENABLED".equals(i.status())) continue;
            for (PackManifest.Dependency d : i.dependsOn()) {
                PackManifest up = cand.get(d.code());
                if (up != null && !SemVer.range(d.version()).test(up.semver())) {
                    problems.add(new Problem("PACK_DEPENDENT_BROKEN", up.code(),
                        "已安装的 " + i.code() + " 要求 " + d.code() + " " + d.version() + "，不能升级到 " + up.version()));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new LsBizException(problems.get(0).code(), problems.get(0).message(), Map.of("problems", problems));
        }
        return topo(cand);
    }

    private static List<PackManifest> topo(Map<String, PackManifest> cand) {
        Map<String, Set<String>> pending = new LinkedHashMap<>();
        cand.values().forEach(m -> {
            Set<String> deps = new HashSet<>();
            m.dependsOn().forEach(d -> {
                if (cand.containsKey(d.code())) deps.add(d.code());
            });
            pending.put(m.code(), deps);
        });
        List<PackManifest> out = new ArrayList<>();
        TreeSet<String> ready = new TreeSet<>();
        pending.forEach((k, v) -> {
            if (v.isEmpty()) ready.add(k);
        });
        while (!ready.isEmpty()) {
            String c = ready.pollFirst();
            out.add(cand.get(c));
            pending.remove(c);
            pending.forEach((k, v) -> {
                if (v.remove(c) && v.isEmpty()) ready.add(k);
            });
        }
        return out;
    }

    /** 返回一个环的路径（首尾相同），无环时为空。 */
    static List<String> findCycle(Map<String, List<String>> graph) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String start : graph.keySet()) {
            List<String> c = dfs(start, graph, state, stack);
            if (!c.isEmpty()) return c;
        }
        return List.of();
    }

    private static List<String> dfs(String n, Map<String, List<String>> graph, Map<String, Integer> state, Deque<String> stack) {
        Integer s = state.get(n);
        if (s != null && s == 2) return List.of();
        if (s != null && s == 1) {
            List<String> path = new ArrayList<>();
            boolean on = false;
            for (var it = stack.descendingIterator(); it.hasNext(); ) {
                String x = it.next();
                if (x.equals(n)) on = true;
                if (on) path.add(x);
            }
            path.add(n);
            return path;
        }
        state.put(n, 1);
        stack.push(n);
        for (String d : graph.getOrDefault(n, List.of())) {
            List<String> c = dfs(d, graph, state, stack);
            if (!c.isEmpty()) return c;
        }
        stack.pop();
        state.put(n, 2);
        return List.of();
    }
}
