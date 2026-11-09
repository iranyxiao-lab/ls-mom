package com.lsmom.crud.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.crud.ddl.LedgerPublisher;
import com.lsmom.crud.ddl.PackMigrator;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Pack 安装器使用的台账发布与破坏性变更评审接口（需 {@code lsmom:pack:install}）。
 * 设计器只能提交变更集，不能直接执行 DDL；DDL 一律经 {@link PackMigrator}（Flyway）执行并留痕。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerAdminController {

    private final LedgerPublisher publisher;
    private final PackMigrator migrator;
    private final ActorResolver actors;

    public LedgerAdminController(LedgerPublisher publisher, PackMigrator migrator, ActorResolver actors) {
        this.publisher = publisher;
        this.migrator = migrator;
        this.actors = actors;
    }

    public record FieldSpec(String code, String displayName, DataType dataType, boolean required, String refConcept, List<String> options,
                            Map<String, Object> props) {
        FieldDef toDef() {
            FieldDef f = new FieldDef(code, displayName, dataType, MetaNames.toColumn(code), required, refConcept, options, false, false,
                FieldDef.FieldStatus.ACTIVE, 0, props);
            return f;
        }
    }

    public record ConceptSpec(String code, String displayName, List<FieldSpec> fields) {
    }

    public record PublishBody(String packCode, ConceptSpec master, List<ConceptSpec> children) {
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/publish")
    public R<LedgerPublisher.Published> publish(@RequestBody PublishBody b) {
        ConceptDef master = ConceptDef.managed(b.master().code(), b.master().displayName(),
            b.master().fields().stream().map(FieldSpec::toDef).toList(), b.packCode());
        List<ConceptDef> children = b.children() == null ? List.of() : b.children().stream()
            .map(c -> ConceptDef.child(c.code(), c.displayName(), c.fields().stream().map(FieldSpec::toDef).toList(), b.packCode(), master.code()))
            .toList();
        return R.ok(publisher.publish(master, children, b.packCode(), actors.current().effectiveParty()));
    }

    public record TypeChange(DataType dataType) {
    }

    /** 设计器提交改类型：只生成待评审的变更集与迁移脚本。 */
    @SaCheckPermission("lsmom:ledger:design")
    @PostMapping("/{concept}/fields/{field}/type-change")
    public R<Long> proposeTypeChange(@PathVariable String concept, @PathVariable String field, @RequestBody TypeChange b) {
        return R.ok(publisher.proposeTypeChange(concept, field, b.dataType(), actors.current().effectiveParty()));
    }

    public record Review(boolean approve) {
    }

    @SaCheckPermission("lsmom:changeset:review")
    @PostMapping("/changeset/{id}/review")
    public R<Void> review(@PathVariable long id, @RequestBody Review b) {
        publisher.review(id, b.approve(), actors.current().effectiveParty());
        return R.ok();
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/changeset/{id}/apply")
    public R<Void> apply(@PathVariable long id) {
        publisher.applyChangeSet(id, actors.current().effectiveParty());
        return R.ok();
    }

    @SaCheckPermission("lsmom:pack:install")
    @GetMapping("/{concept}/migrations")
    public R<List<Map<String, Object>>> migrations(@PathVariable String concept) {
        return R.ok(migrator.history(concept));
    }
}
