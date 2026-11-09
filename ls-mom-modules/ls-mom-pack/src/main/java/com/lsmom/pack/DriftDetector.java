package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配置漂移：比较运行库中的定义（规范化哈希）与安装时记录的基线。手工改表、在设计器里另发版本都会被发现；
 * 差异可一键回灌为新的 ChangeSet（基于已安装制品，替换漂移的文件并把版本号加一个修订号）。
 */
public class DriftDetector {

    public enum State { SAME, CHANGED, MISSING }

    public record ItemDrift(PackDefinitions.Kind kind, String code, String path, State state, Integer expectedVersion, Integer actualVersion,
                            List<PackJson.Diff> diffs) {
    }

    public record Report(String pack, String version, boolean drifted, List<ItemDrift> items) {
        public List<ItemDrift> changed() {
            return items.stream().filter(i -> i.state() != State.SAME).toList();
        }
    }

    private final PackStore store;
    private final PackDefinitions defs;
    private final ChangeSetPipeline pipeline;

    public DriftDetector(PackStore store, PackDefinitions defs, ChangeSetPipeline pipeline) {
        this.store = store;
        this.defs = defs;
        this.pipeline = pipeline;
    }

    public Report report(String code) {
        PackStore.Pack p = store.get(code);
        List<ItemDrift> out = new ArrayList<>();
        for (PackStore.Item i : store.items(code)) {
            if (i.kind() == PackDefinitions.Kind.MIGRATION || i.kind() == PackDefinitions.Kind.SEED) continue;
            boolean ext = i.definition().hasNonNull("extends");
            Optional<PackDefinitions.Snapshot> cur = defs.current(i.kind(), i.code(), code, ext);
            if (cur.isEmpty()) {
                out.add(new ItemDrift(i.kind(), i.code(), i.path(), State.MISSING, i.version(), null, List.of()));
                continue;
            }
            String h = PackJson.hash(cur.get().form());
            // Concept 的版本号随任何 Pack 的发布递增（含其他 Pack 追加字段），只比较本 Pack 拥有的内容
            boolean same = h.equals(i.hash()) && (i.kind() == PackDefinitions.Kind.CONCEPT || java.util.Objects.equals(cur.get().version(), i.version()));
            out.add(new ItemDrift(i.kind(), i.code(), i.path(), same ? State.SAME : State.CHANGED, i.version(), cur.get().version(),
                same ? List.of() : PackJson.diff(i.definition(), cur.get().form())));
        }
        return new Report(code, p.version(), out.stream().anyMatch(d -> d.state() != State.SAME), out);
    }

    /** 把漂移回灌为 ChangeSet：生成 修订号+1 的新制品，其中漂移的文件替换为运行库中的当前定义。 */
    public ChangeSetPipeline.ChangeSet toChangeSet(String code, Long by) {
        Report r = report(code);
        List<ItemDrift> changed = r.changed().stream().filter(d -> d.state() == State.CHANGED).toList();
        if (changed.isEmpty()) throw new LsBizException("PACK_NO_DRIFT", code + " 没有可回灌的配置漂移");
        PackStore.Pack p = store.get(code);
        PackSource src = store.artifact(p.artifactSha());
        Map<String, byte[]> files = new LinkedHashMap<>();
        ArrayNode items = PackJson.JSON.createArrayNode();
        for (ItemDrift d : changed) {
            boolean ext = store.item(code, d.kind(), d.code()).map(i -> i.definition().hasNonNull("extends")).orElse(false);
            JsonNode form = defs.current(d.kind(), d.code(), code, ext).orElseThrow().form();
            files.put(d.path(), PackJson.write(d.path(), form));
            ObjectNode it = items.addObject();
            it.put("type", "DRIFT");
            it.put("kind", d.kind().name());
            it.put("code", d.code());
            it.put("path", d.path());
            it.set("diffs", PackJson.JSON.valueToTree(d.diffs()));
        }
        ObjectNode manifest = (ObjectNode) PackJson.read(PackSource.MANIFEST, src.file(PackSource.MANIFEST));
        manifest.put("version", SemVer.parse(p.version()).nextPatch().toString());
        files.put(PackSource.MANIFEST, PackJson.write(PackSource.MANIFEST, manifest));
        return pipeline.submit(src.with(files), by, "DRIFT", items);
    }
}
