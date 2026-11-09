package com.lsmom.pack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** meta_pack / meta_pack_item / pack_artifact 读写。 */
public class PackStore {

    public record Pack(String code, String version, String name, String status, boolean standard, String owner, Long ownerParty,
                       String requester, LocalDate reviewDue, String reviewStatus, LocalDateTime reviewNotifiedAt,
                       List<PackManifest.Dependency> dependsOn, JsonNode manifest, String artifactSha, String previousVersion, String error,
                       LocalDateTime installedAt, LocalDateTime disabledAt) {
    }

    public record Item(String pack, PackDefinitions.Kind kind, String code, Integer version, String path, String hash, String sourceHash,
                       JsonNode definition, LocalDateTime installedAt) {
    }

    private static final TypeReference<List<PackManifest.Dependency>> DEPS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public PackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Pack> packMapper = (rs, i) -> new Pack(rs.getString("code"), rs.getString("version"), rs.getString("display_name"),
        rs.getString("status"), rs.getBoolean("standard"), rs.getString("owner"), (Long) rs.getObject("owner_party"), rs.getString("requester"),
        rs.getObject("review_due", LocalDate.class), rs.getString("review_status"), rs.getObject("review_notified_at", LocalDateTime.class),
        read(rs.getString("depends_on"), DEPS), read(rs.getString("manifest")), rs.getString("artifact_sha"), rs.getString("previous_version"),
        rs.getString("error"), rs.getObject("installed_at", LocalDateTime.class), rs.getObject("disabled_at", LocalDateTime.class));

    private final RowMapper<Item> itemMapper = (rs, i) -> new Item(rs.getString("pack_code"), PackDefinitions.Kind.valueOf(rs.getString("kind")),
        rs.getString("code"), (Integer) rs.getObject("version"), rs.getString("path"), rs.getString("hash"), rs.getString("source_hash"),
        read(rs.getString("definition")), rs.getObject("installed_at", LocalDateTime.class));

    public List<Pack> list() {
        return jdbc.query("select * from meta_pack where status <> 'UNINSTALLED' order by standard desc, code", packMapper);
    }

    public Optional<Pack> find(String code) {
        return jdbc.query("select * from meta_pack where code = ?", packMapper, code).stream().findFirst();
    }

    public Pack get(String code) {
        return find(code).orElseThrow(() -> LsBizException.notFound("Pack", code));
    }

    public List<DependencyPlanner.Installed> installed() {
        return list().stream().map(p -> new DependencyPlanner.Installed(p.code(), p.version(), p.status(), p.dependsOn())).toList();
    }

    /** 依赖指定 Pack 的其他已启用 Pack。 */
    public List<String> dependents(String code) {
        return list().stream().filter(p -> "ENABLED".equals(p.status()) && !p.code().equals(code))
            .filter(p -> p.dependsOn().stream().anyMatch(d -> d.code().equals(code))).map(Pack::code).toList();
    }

    public List<Item> items(String pack) {
        return jdbc.query("select * from meta_pack_item where pack_code = ? order by kind, code", itemMapper, pack);
    }

    public Optional<Item> item(String pack, PackDefinitions.Kind kind, String code) {
        return jdbc.query("select * from meta_pack_item where pack_code = ? and kind = ? and code = ?", itemMapper, pack, kind.name(), code)
            .stream().findFirst();
    }

    public void saveItem(Item i) {
        jdbc.update("""
            insert into meta_pack_item (pack_code, kind, code, version, path, hash, source_hash, definition, installed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            on conflict (pack_code, kind, code) do update set version = excluded.version, path = excluded.path, hash = excluded.hash,
                source_hash = excluded.source_hash, definition = excluded.definition, installed_at = now()""",
            i.pack(), i.kind().name(), i.code(), i.version(), i.path(), i.hash(), i.sourceHash(), i.definition().toString());
    }

    public void removeItemsNotIn(String pack, List<String> keys) {
        for (Item i : items(pack)) {
            if (!keys.contains(i.kind() + ":" + i.code())) {
                jdbc.update("delete from meta_pack_item where pack_code = ? and kind = ? and code = ?", pack, i.kind().name(), i.code());
            }
        }
    }

    /** 保存制品（以内容摘要为主键，重复上传同一内容不产生新记录）。 */
    public String saveArtifact(PackSource src, PackManifest m, Long by) {
        String sha = src.digest();
        jdbc.update("""
            insert into pack_artifact (sha256, code, version, manifest, content, uploaded_by) values (?, ?, ?, ?::jsonb, ?, ?)
            on conflict (sha256) do nothing""", sha, m.code(), m.version(), m.toJson().toString(), src.toZip(), by);
        return sha;
    }

    public PackSource artifact(String sha) {
        byte[] zip = jdbc.query("select content from pack_artifact where sha256 = ?", (rs, i) -> rs.getBytes(1), sha).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("Pack 制品", sha));
        return PackSource.fromZip(zip);
    }

    public byte[] artifactZip(String sha) {
        return jdbc.query("select content from pack_artifact where sha256 = ?", (rs, i) -> rs.getBytes(1), sha).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("Pack 制品", sha));
    }

    /** 已上传但未安装的更高版本（待升级）。 */
    public Optional<String> latestArtifactVersion(String code) {
        return jdbc.queryForList("select version from pack_artifact where code = ?", String.class, code).stream()
            .max((a, b) -> SemVer.parse(a).compareTo(SemVer.parse(b)));
    }

    static JsonNode read(String s) {
        try {
            return s == null ? PackJson.JSON.nullNode() : PackJson.JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T read(String s, TypeReference<T> t) {
        try {
            return PackJson.JSON.readValue(s == null ? "[]" : s, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
