package com.lsmom.core.document;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.core.party.PartyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 附件关联与受控下载。下载前校验：关联有效、请求人数据域覆盖附件组织、请求人密级不低于附件密级；
 * 无论放行还是拒绝都写访问审计。
 */
@Service
public class DocumentService {

    static final RowMapper<DocumentLink> MAPPER = (rs, i) -> new DocumentLink(rs.getLong("id"), rs.getLong("oss_id"),
        rs.getString("object_type"), rs.getString("object_id"), rs.getString("purpose"),
        Classification.valueOf(rs.getString("classification")), rs.getInt("doc_version"), rs.getString("file_name"),
        (Long) rs.getObject("org_unit_id"), (Long) rs.getObject("created_by"));

    private final JdbcTemplate jdbc;
    private final PartyService parties;
    private final AccessAuditService audit;
    private final DocumentContentSource content;

    public DocumentService(JdbcTemplate jdbc, PartyService parties, AccessAuditService audit, DocumentContentSource content) {
        this.jdbc = jdbc;
        this.parties = parties;
        this.audit = audit;
        this.content = content;
    }

    public record AttachCommand(long ossId, String objectType, String objectId, String purpose, Classification classification,
                                Long orgUnitId, Long createdBy) {
    }

    /** 关联附件；同一对象再次关联同一文件视为新版本。 */
    @Transactional
    public DocumentLink attach(AttachCommand cmd) {
        List<String> names = jdbc.queryForList("select original_name from sys_oss where oss_id = ?", String.class, cmd.ossId());
        if (names.isEmpty()) throw LsBizException.notFound("文件", cmd.ossId());
        Long id = jdbc.queryForObject("""
            insert into core_document_link (oss_id, object_type, object_id, purpose, classification, file_name, org_unit_id, created_by)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (object_type, object_id, oss_id) do update set doc_version = core_document_link.doc_version + 1,
              removed_at = null, classification = excluded.classification
            returning id""", Long.class, cmd.ossId(), cmd.objectType(), cmd.objectId(),
            cmd.purpose() == null ? "ATTACHMENT" : cmd.purpose(),
            (cmd.classification() == null ? Classification.INTERNAL : cmd.classification()).name(), names.get(0),
            cmd.orgUnitId(), cmd.createdBy());
        return get(id);
    }

    public DocumentLink get(long id) {
        List<DocumentLink> list = jdbc.query("select * from core_document_link where id = ?", MAPPER, id);
        if (list.isEmpty()) throw LsBizException.notFound("附件", id);
        return list.get(0);
    }

    public List<DocumentLink> listByObject(String objectType, String objectId) {
        return jdbc.query("select * from core_document_link where object_type = ? and object_id = ? and removed_at is null order by id",
            MAPPER, objectType, objectId);
    }

    @Transactional
    public void detach(long linkId) {
        jdbc.update("update core_document_link set removed_at = now() where id = ?", linkId);
    }

    public record Download(DocumentLink link, InputStream stream) {
    }

    /**
     * 受控下载。{@code scope} 为请求人在该业务对象上的数据域（由调用方按其角色解析）。
     */
    public Download download(long linkId, long requesterParty, DataScope scope) {
        List<DocumentLink> found = jdbc.query("select * from core_document_link where id = ? and removed_at is null", MAPPER, linkId);
        if (found.isEmpty()) {
            audit.record(requesterParty, "DOCUMENT_DOWNLOAD", "DOCUMENT_LINK", String.valueOf(linkId), false, "附件不存在或已移除");
            throw LsBizException.notFound("附件", linkId);
        }
        DocumentLink link = found.get(0);
        Party requester = parties.get(requesterParty);
        String denied = denyReason(link, requester, scope);
        if (denied != null) {
            audit.record(requesterParty, "DOCUMENT_DOWNLOAD", "DOCUMENT_LINK", String.valueOf(linkId), false, denied);
            throw new LsBizException("ACCESS_DENIED", "无权下载该附件：" + denied, Map.of("linkId", linkId));
        }
        audit.record(requesterParty, "DOCUMENT_DOWNLOAD", "DOCUMENT_LINK", String.valueOf(linkId), true, null);
        try {
            return new Download(link, content.open(link.ossId()));
        } catch (IOException e) {
            throw new LsBizException("DOCUMENT_UNAVAILABLE", "附件读取失败，请稍后重试");
        }
    }

    private String denyReason(DocumentLink link, Party requester, DataScope scope) {
        if (!"ACTIVE".equals(requester.status())) return "主体已停用";
        String clearance = jdbc.queryForObject("select clearance from core_party where id = ?", String.class, requester.id());
        if (!link.classification().allowedFor(Classification.valueOf(clearance))) {
            return "附件密级为 " + link.classification() + "，超出访问密级";
        }
        if (scope.all()) return null;
        if (scope.level() == DataScopeLevel.SELF) {
            return requester.id().equals(link.createdBy()) ? null : "仅本人数据域，只能下载自己上传的附件";
        }
        return scope.allowsOrg(link.orgUnitId()) ? null : "不在该附件所属组织的数据域内";
    }
}
