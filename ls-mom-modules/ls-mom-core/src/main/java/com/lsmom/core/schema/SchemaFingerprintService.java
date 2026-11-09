package com.lsmom.core.schema;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 计算与记录表结构指纹。
 * <p>
 * 指纹是规范化的「表.列 类型 可空 默认值」与索引定义清单。每次迁移（核心或 Pack）完成后记录一次；
 * 应用启动时若没有新迁移，就把当前结构与最近一次记录比对，不一致即为手工 DDL 漂移。
 */
@RequiredArgsConstructor
public class SchemaFingerprintService {

    static final String TABLE = "ls_schema_fingerprint";

    private static final String COLUMNS_SQL = """
        select 'col ' || c.table_schema || '.' || c.table_name || '.' || c.column_name
               || ' ' || c.data_type
               || coalesce('(' || c.character_maximum_length || ')', '')
               || case when c.is_nullable = 'NO' then ' not null' else '' end
               || coalesce(' default ' || c.column_default, '')
          from information_schema.columns c
          join information_schema.tables t
            on t.table_schema = c.table_schema and t.table_name = c.table_name
         where c.table_schema in (:schemas)
           and t.table_type = 'BASE TABLE'
           and c.table_name not like 'flyway\\_%'
           and c.table_name <> 'ls_schema_fingerprint'
        """;

    private static final String INDEXES_SQL = """
        select 'idx ' || indexdef
          from pg_indexes
         where schemaname in (:schemas)
           and tablename not like 'flyway\\_%'
           and tablename <> 'ls_schema_fingerprint'
        """;

    private final JdbcTemplate jdbc;
    private final SchemaDriftProperties properties;

    /** 当前数据库结构的规范化快照（已排序，每行一项）。 */
    public List<String> snapshot() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        MapSqlParameterSource params = new MapSqlParameterSource("schemas", properties.getSchemas());
        List<String> lines = new ArrayList<>(named.queryForList(COLUMNS_SQL, params, String.class));
        lines.addAll(named.queryForList(INDEXES_SQL, params, String.class));
        lines.sort(String::compareTo);
        return lines;
    }

    /** 记录当前结构为最新基准。迁移执行后由迁移方（核心 Flyway、Pack 安装器）调用。 */
    public void record(String version, String reason) {
        List<String> lines = snapshot();
        String text = String.join("\n", lines);
        jdbc.update("insert into " + TABLE + " (version, reason, hash, snapshot) values (?, ?, ?, ?)",
            version, reason, sha256(text), text);
    }

    /** 最近一次记录的快照；从未记录过时为空。 */
    public Optional<List<String>> latestRecorded() {
        List<String> rows = jdbc.queryForList(
            "select snapshot from " + TABLE + " order by id desc limit 1", String.class);
        return rows.stream().findFirst().map(s -> s.isEmpty() ? List.of() : List.of(s.split("\n")));
    }

    /** 比对当前结构与最近记录，返回差异（无差异或从未记录时为空列表）。 */
    public List<String> diffAgainstLatest() {
        Optional<List<String>> recorded = latestRecorded();
        if (recorded.isEmpty()) {
            return List.of();
        }
        Set<String> expected = new LinkedHashSet<>(recorded.get());
        Set<String> actual = new LinkedHashSet<>(snapshot());
        List<String> diff = new ArrayList<>();
        expected.stream().filter(l -> !actual.contains(l)).forEach(l -> diff.add("- " + l));
        actual.stream().filter(l -> !expected.contains(l)).forEach(l -> diff.add("+ " + l));
        return diff;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
