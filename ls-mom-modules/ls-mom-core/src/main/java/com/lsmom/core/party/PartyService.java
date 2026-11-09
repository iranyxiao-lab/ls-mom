package com.lsmom.core.party;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主体服务。员工主体由登录账号自动生成（触发器），这里负责外部协作方、服务与 Agent 身份的登记与查询。
 */
@Service
public class PartyService {

    static final String COLUMNS = "id, code, party_type, name, user_id, phone, org_name, status";
    static final RowMapper<Party> MAPPER = (rs, i) -> new Party(rs.getLong("id"), rs.getString("code"),
        PartyType.valueOf(rs.getString("party_type")), rs.getString("name"), (Long) rs.getObject("user_id"),
        rs.getString("phone"), rs.getString("org_name"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public PartyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RegisterParty(PartyType type, String name, String phone, String orgName, Long orgUnitId) {
    }

    /** 登记不登录的主体（外部协作方、外包人员、服务身份、Agent 身份）。 */
    @Transactional
    public Party register(RegisterParty cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.type() == null) errors.put("type", "类型必填");
        else if (cmd.type() == PartyType.EMPLOYEE) errors.put("type", "员工主体随登录账号自动生成，不能单独登记");
        if (cmd.name() == null || cmd.name().isBlank()) errors.put("name", "名称必填");
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        String prefix = switch (cmd.type()) {
            case OUTSOURCE -> "O";
            case SERVICE -> "S";
            case AGENT -> "A";
            default -> "X";
        };
        Long id = jdbc.queryForObject("""
            insert into core_party (code, party_type, name, phone, org_name)
            values (? || nextval('core_party_code_seq'), ?, ?, ?, ?) returning id""",
            Long.class, prefix, cmd.type().name(), cmd.name(), cmd.phone(), cmd.orgName());
        if (cmd.orgUnitId() != null) {
            jdbc.update("insert into core_party_org (party_id, org_unit_id, role, is_primary) values (?, ?, 'MEMBER', true)", id, cmd.orgUnitId());
        }
        return get(id);
    }

    public Party get(long id) {
        List<Party> list = jdbc.query("select " + COLUMNS + " from core_party where id = ?", MAPPER, id);
        if (list.isEmpty()) throw LsBizException.notFound("主体", id);
        return list.get(0);
    }

    public Optional<Party> findByUserId(long userId) {
        return jdbc.query("select " + COLUMNS + " from core_party where user_id = ?", MAPPER, userId).stream().findFirst();
    }

    /** 当前登录账号对应的主体；账号必然有主体，缺失说明映射被破坏。 */
    public Party requireByUserId(long userId) {
        return findByUserId(userId).orElseThrow(() -> new LsBizException("PARTY_MISSING", "账号未映射到主体：" + userId));
    }

    /** 主体的主组织（当前有效）。 */
    public Optional<Long> primaryOrgUnitId(long partyId) {
        return jdbc.queryForList("""
            select org_unit_id from core_party_org
            where party_id = ? and is_primary and (valid_to is null or valid_to >= current_date)""", Long.class, partyId)
            .stream().findFirst();
    }
}
