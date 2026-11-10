package com.lsmom.action.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.summary.SummaryService;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 摘要查看（按数据域过滤）；草拟走 ls.core.Summary.draft，确认发布 / 作废走 /api/op 的状态迁移。 */
@RestController
@RequestMapping("/api/summaries")
public class SummaryController {

    private final SummaryService summaries;
    private final ActorResolver actors;

    public SummaryController(SummaryService summaries, ActorResolver actors) {
        this.summaries = summaries;
        this.actors = actors;
    }

    @SaCheckPermission(value = {"lsmom:summary:draft", "lsmom:summary:confirm"}, mode = SaMode.OR)
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String kind, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String subjectConcept, @RequestParam(required = false) String subjectId) {
        return R.ok(summaries.list(kind, status, subjectConcept, subjectId, actors.current()));
    }

    @SaCheckPermission(value = {"lsmom:summary:draft", "lsmom:summary:confirm"}, mode = SaMode.OR)
    @GetMapping("/kinds")
    public R<Set<String>> kinds() {
        return R.ok(summaries.kinds());
    }

    @SaCheckPermission(value = {"lsmom:summary:draft", "lsmom:summary:confirm"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable long id) {
        ActorContext a = actors.current();
        Map<String, Object> s = summaries.find(id).orElseThrow(() -> LsBizException.notFound("摘要", id));
        Object org = s.get("orgUnitId");
        if (!a.dataScope().allowsOrg(org == null ? null : ((Number) org).longValue())) throw LsBizException.notFound("摘要", id);
        return R.ok(s);
    }
}
