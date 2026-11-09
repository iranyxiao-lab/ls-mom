package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作关系：父子、依赖、关联、阻塞。父子关系（from 为父、to 为子）不允许成环，每个工作最多一个父工作。
 */
@Service
public class WorkRelationService {

    public enum RelationType { PARENT, DEPENDS, RELATED, BLOCKS }

    public record Relation(long fromId, long toId, RelationType type) {
    }

    private final JdbcTemplate jdbc;
    private final WorkItemService works;

    public WorkRelationService(JdbcTemplate jdbc, WorkItemService works) {
        this.jdbc = jdbc;
        this.works = works;
    }

    @Transactional
    public void link(long fromId, long toId, RelationType type) {
        if (fromId == toId) throw new LsBizException("RELATION_SELF", "工作不能与自身建立关系");
        works.get(fromId);
        works.get(toId);
        if (type == RelationType.PARENT || type == RelationType.DEPENDS) {
            // 串行化同类关系写入，避免并发下各自通过检查后共同成环
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext('core_work_relation:' || ?))", type.name());
        }
        if (type == RelationType.PARENT) {
            Integer parents = jdbc.queryForObject(
                "select count(*) from core_work_relation where to_id = ? and relation_type = 'PARENT' and from_id <> ?",
                Integer.class, toId, fromId);
            if (parents != null && parents > 0) throw new LsBizException("RELATION_MULTI_PARENT", "该工作已有父工作");
        }
        if (type == RelationType.PARENT || type == RelationType.DEPENDS) {
            List<Long> path = pathBetween(toId, fromId, type);
            if (!path.isEmpty()) {
                throw new LsBizException("RELATION_CYCLE", "形成循环：" + (type == RelationType.PARENT ? "该工作已是对方的祖先" : "依赖链成环"),
                    Map.of("cycle", path));
            }
        }
        jdbc.update("insert into core_work_relation (from_id, to_id, relation_type) values (?, ?, ?) on conflict do nothing",
            fromId, toId, type.name());
    }

    @Transactional
    public void unlink(long fromId, long toId, RelationType type) {
        jdbc.update("delete from core_work_relation where from_id = ? and to_id = ? and relation_type = ?", fromId, toId, type.name());
    }

    /** 拆分：在父工作下创建若干子工作，继承类型、组织、Profile 与来源。 */
    @Transactional
    public List<WorkItem> split(long parentId, List<String> childTitles, Long createdBy) {
        if (childTitles == null || childTitles.isEmpty()) throw LsBizException.validation(Map.of("children", "至少拆出一个子工作"));
        WorkItem parent = works.get(parentId);
        if (parent.status().terminal()) throw new LsBizException("WORK_CLOSED", "已结束的工作不能拆分");
        List<WorkItem> children = new ArrayList<>();
        for (String title : childTitles) {
            WorkItem child = works.create(new WorkItemService.CreateWorkItem(parent.type(), title, "", parent.orgUnitId(),
                parent.constraintProfile(), parent.priority(), parent.plannedStart(), parent.plannedEnd(),
                null, null, parent.sourceType(), parent.sourceId(), createdBy));
            link(parentId, child.id(), RelationType.PARENT);
            children.add(child);
        }
        return children;
    }

    public List<Relation> relationsOf(long workId) {
        return jdbc.query("select from_id, to_id, relation_type from core_work_relation where from_id = ? or to_id = ? order by id",
            (rs, i) -> new Relation(rs.getLong(1), rs.getLong(2), RelationType.valueOf(rs.getString(3))), workId, workId);
    }

    public List<Long> childrenOf(long parentId) {
        return jdbc.queryForList("select to_id from core_work_relation where from_id = ? and relation_type = 'PARENT' order by to_id",
            Long.class, parentId);
    }

    /** 从 start 沿关系方向能否到达 target；能则返回路径（含两端），否则为空。 */
    private List<Long> pathBetween(long start, long target, RelationType type) {
        List<String> found = jdbc.queryForList("""
            with recursive walk(node, path) as (
              select ?::bigint, array[?::bigint]
              union all
              select r.to_id, walk.path || r.to_id
              from core_work_relation r join walk on r.from_id = walk.node
              where r.relation_type = ? and not r.to_id = any(walk.path)
            ) select array_to_string(path, ',') from walk where node = ? limit 1""", String.class, start, start, type.name(), target);
        if (found.isEmpty()) return List.of();
        return java.util.Arrays.stream(found.get(0).split(",")).map(Long::valueOf).toList();
    }
}
