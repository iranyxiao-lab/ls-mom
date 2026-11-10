package com.lsmom.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.Party;
import com.lsmom.core.party.PartyService;
import com.lsmom.core.party.PartyType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Agent 服务身份与 API Key：
 * <ul>
 *   <li>服务身份对应一个 AGENT 类型主体，带权限上限（与代理的真人权限取交集）和每分钟调用上限；</li>
 *   <li>Key 形如 {@code lsk_<前缀>_<密文>}，库中只存 SHA-256 摘要，明文只在签发 / 轮换时返回一次；</li>
 *   <li>过期、吊销、停用、超限的使用被拒绝并记入 agent_key_event；轮换后旧 Key 在宽限期内仍可用。</li>
 * </ul>
 */
@Service
public class AgentRegistry {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern KEY = Pattern.compile("^lsk_([a-z0-9]{12})_([A-Za-z0-9]{40})$");
    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    static final int MAX_DAYS = 365;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Identity(long id, String code, String name, long partyId, Set<String> permissions, int rateLimitPerMinute, String status,
                           String description, LocalDateTime createdAt) {
    }

    public record KeyInfo(long id, long agentId, String prefix, String status, LocalDateTime expiresAt, Long rotatedTo, LocalDateTime createdAt,
                          LocalDateTime revokedAt, String revokeReason, LocalDateTime lastUsedAt) {
    }

    /** 签发结果：明文 Key 只在这里出现一次。 */
    public record Issued(String key, KeyInfo info) {
    }

    /** 认证通过的 Agent。 */
    public record Principal(Identity identity, long keyId, String prefix) {
    }

    private final JdbcTemplate jdbc;
    private final PartyService parties;
    private final Clock clock;
    private final TransactionTemplate newTx;
    private final Map<Long, long[]> windows = new ConcurrentHashMap<>();

    public AgentRegistry(JdbcTemplate jdbc, PartyService parties, Clock clock, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.parties = parties;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // —— 服务身份 ——

    public Identity create(String code, String name, Set<String> permissions, Integer rateLimitPerMinute, String description, Long by) {
        if (code == null || !CODE.matcher(code).matches()) throw LsBizException.validation(Map.of("code", "编码为 3～64 位小写字母、数字或连字符，字母开头"));
        if (name == null || name.isBlank()) throw LsBizException.validation(Map.of("name", "名称必填"));
        Integer n = jdbc.queryForObject("select count(*) from agent_identity where code = ?", Integer.class, code);
        if (n != null && n > 0) throw LsBizException.validation(Map.of("code", "编码已存在"));
        Party p = parties.register(new PartyService.RegisterParty(PartyType.AGENT, name.trim(), null, null, null));
        Long id = jdbc.queryForObject("""
            insert into agent_identity (code, name, party_id, permissions, rate_limit_per_minute, description, created_by)
            values (?, ?, ?, ?::jsonb, ?, ?, ?) returning id""", Long.class, code, name.trim(), p.id(), json(permissions),
            rateLimitPerMinute == null ? 60 : rateLimitPerMinute, description, by);
        return get(id);
    }

    public Identity updatePermissions(long id, Set<String> permissions, Integer rateLimitPerMinute) {
        get(id);
        jdbc.update("update agent_identity set permissions = ?::jsonb, rate_limit_per_minute = coalesce(?, rate_limit_per_minute), updated_at = now() where id = ?",
            json(permissions), rateLimitPerMinute, id);
        return get(id);
    }

    public Identity setStatus(long id, boolean active) {
        get(id);
        jdbc.update("update agent_identity set status = ?, updated_at = now() where id = ?", active ? "ACTIVE" : "DISABLED", id);
        return get(id);
    }

    public Identity get(long id) {
        return jdbc.query("select * from agent_identity where id = ?", (rs, i) -> identity(rs), id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("Agent", id));
    }

    public Identity byParty(long partyId) {
        return jdbc.query("select * from agent_identity where party_id = ?", (rs, i) -> identity(rs), partyId).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("Agent", partyId));
    }

    public List<Identity> list() {
        return jdbc.query("select * from agent_identity order by id", (rs, i) -> identity(rs));
    }

    // —— API Key ——

    public Issued issue(long agentId, Integer expiresInDays, Long by) {
        Identity a = get(agentId);
        int days = expiresInDays == null ? 90 : expiresInDays;
        if (days < 1 || days > MAX_DAYS) throw LsBizException.validation(Map.of("expiresInDays", "有效期 1～" + MAX_DAYS + " 天"));
        String prefix = random(12).toLowerCase();
        String secret = random(40);
        String key = "lsk_" + prefix + "_" + secret;
        Long id = jdbc.queryForObject("insert into agent_api_key (agent_id, prefix, key_hash, expires_at, created_by) values (?, ?, ?, ?, ?) returning id",
            Long.class, a.id(), prefix, sha256(key), LocalDateTime.now(clock).plusDays(days), by);
        event(prefix, a.id(), "ISSUED", by, "有效期 " + days + " 天");
        return new Issued(key, key(id));
    }

    /** 轮换：签发新 Key；旧 Key 在宽限期（默认 24 小时，0 表示立即失效）后失效。 */
    public Issued rotate(long keyId, Integer graceHours, Integer expiresInDays, Long by) {
        KeyInfo old = key(keyId);
        if (!"ACTIVE".equals(old.status())) throw new LsBizException("AGENT_KEY_REVOKED", "已吊销的 Key 不能轮换");
        int grace = graceHours == null ? 24 : graceHours;
        if (grace < 0 || grace > 168) throw LsBizException.validation(Map.of("graceHours", "宽限期 0～168 小时"));
        Issued n = issue(old.agentId(), expiresInDays, by);
        LocalDateTime until = LocalDateTime.now(clock).plusHours(grace);
        jdbc.update("update agent_api_key set rotated_to = ?, expires_at = least(expires_at, ?) where id = ?", n.info().id(), until, keyId);
        event(old.prefix(), old.agentId(), "ROTATED", by, "新 Key " + n.info().prefix() + "，旧 Key 宽限 " + grace + " 小时");
        return n;
    }

    public KeyInfo revoke(long keyId, String reason, Long by) {
        KeyInfo k = key(keyId);
        jdbc.update("update agent_api_key set status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ? where id = ? and status = 'ACTIVE'",
            by, reason, keyId);
        event(k.prefix(), k.agentId(), "REVOKED", by, reason);
        return key(keyId);
    }

    public KeyInfo key(long id) {
        return jdbc.query("select * from agent_api_key where id = ?", (rs, i) -> keyInfo(rs), id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("API Key", id));
    }

    public List<KeyInfo> keys(long agentId) {
        return jdbc.query("select * from agent_api_key where agent_id = ? order by id desc", (rs, i) -> keyInfo(rs), agentId);
    }

    public List<Map<String, Object>> events(long agentId, int limit) {
        return jdbc.queryForList("select occurred_at, prefix, event, actor_party, detail from agent_key_event where agent_id = ? order by id desc limit ?",
            agentId, Math.min(Math.max(limit, 1), 500));
    }

    /**
     * 认证并计入限流。失败抛出 AGENT_KEY_INVALID / AGENT_KEY_EXPIRED / AGENT_KEY_REVOKED / AGENT_DISABLED / AGENT_RATE_LIMITED，
     * 并（在独立事务中）记录事件。
     */
    public Principal authenticate(String key) {
        if (key == null || key.isBlank()) throw new LsBizException("AGENT_KEY_INVALID", "缺少 Agent API Key（X-Agent-Key）");
        var m = KEY.matcher(key.trim());
        if (!m.matches()) {
            reject(null, null, "INVALID", "格式错误");
            throw new LsBizException("AGENT_KEY_INVALID", "Agent API Key 无效");
        }
        String prefix = m.group(1);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from agent_api_key where prefix = ?", prefix);
        if (rows.isEmpty() || !MessageDigest.isEqual(((String) rows.get(0).get("key_hash")).getBytes(StandardCharsets.US_ASCII),
            sha256(key.trim()).getBytes(StandardCharsets.US_ASCII))) {
            reject(prefix, rows.isEmpty() ? null : ((Number) rows.get(0).get("agent_id")).longValue(), "INVALID", "摘要不匹配");
            throw new LsBizException("AGENT_KEY_INVALID", "Agent API Key 无效");
        }
        KeyInfo k = key(((Number) rows.get(0).get("id")).longValue());
        if ("REVOKED".equals(k.status())) {
            reject(prefix, k.agentId(), "REVOKED_USE", k.revokeReason());
            throw new LsBizException("AGENT_KEY_REVOKED", "Agent API Key 已吊销");
        }
        if (!k.expiresAt().isAfter(LocalDateTime.now(clock))) {
            reject(prefix, k.agentId(), "EXPIRED_USE", "过期于 " + k.expiresAt());
            throw new LsBizException("AGENT_KEY_EXPIRED", "Agent API Key 已过期");
        }
        Identity a = get(k.agentId());
        if (!"ACTIVE".equals(a.status())) {
            reject(prefix, a.id(), "DISABLED_USE", null);
            throw new LsBizException("AGENT_DISABLED", "Agent「" + a.name() + "」已停用");
        }
        if (!allow(k.id(), a.rateLimitPerMinute())) {
            reject(prefix, a.id(), "RATE_LIMITED", "上限 " + a.rateLimitPerMinute() + " 次/分钟");
            throw new LsBizException("AGENT_RATE_LIMITED", "Agent 调用过于频繁（上限 " + a.rateLimitPerMinute() + " 次/分钟）",
                Map.of("limitPerMinute", a.rateLimitPerMinute()));
        }
        jdbc.update("update agent_api_key set last_used_at = now() where id = ?", k.id());
        return new Principal(a, k.id(), prefix);
    }

    /** 固定窗口（按分钟）计数。 */
    boolean allow(long keyId, int limit) {
        long minute = clock.millis() / Duration.ofMinutes(1).toMillis();
        long[] w = windows.compute(keyId, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        return w[1] <= limit;
    }

    private void reject(String prefix, Long agentId, String event, String detail) {
        newTx.executeWithoutResult(s -> event(prefix, agentId, event, null, detail));
    }

    private void event(String prefix, Long agentId, String event, Long by, String detail) {
        jdbc.update("insert into agent_key_event (prefix, agent_id, event, actor_party, detail) values (?, ?, ?, ?, ?)", prefix, agentId, event, by,
            detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail);
    }

    private Identity identity(java.sql.ResultSet rs) throws java.sql.SQLException {
        Set<String> perms;
        try {
            perms = new LinkedHashSet<>(JSON.readValue(rs.getString("permissions"), new TypeReference<List<String>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Identity(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("party_id"), Set.copyOf(perms),
            rs.getInt("rate_limit_per_minute"), rs.getString("status"), rs.getString("description"), rs.getObject("created_at", LocalDateTime.class));
    }

    private static KeyInfo keyInfo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KeyInfo(rs.getLong("id"), rs.getLong("agent_id"), rs.getString("prefix"), rs.getString("status"),
            rs.getObject("expires_at", LocalDateTime.class), (Long) rs.getObject("rotated_to"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("revoked_at", LocalDateTime.class), rs.getString("revoke_reason"), rs.getObject("last_used_at", LocalDateTime.class));
    }

    private static String json(Set<String> perms) {
        try {
            return JSON.writeValueAsString(perms == null ? List.of() : List.copyOf(new java.util.TreeSet<>(perms)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }
}
