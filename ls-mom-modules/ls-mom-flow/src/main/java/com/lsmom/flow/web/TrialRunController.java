package com.lsmom.flow.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.flow.runtime.TrialRunner;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 设计器沙箱试运行：准备、执行（只回滚事务，不写正式库）、查看与一键清除本人的试运行轨迹。 */
@RestController
@RequestMapping("/api/flow/trial")
public class TrialRunController {

    private final TrialRunner trials;
    private final ActorResolver actors;

    public TrialRunController(TrialRunner trials, ActorResolver actors) {
        this.trials = trials;
        this.actors = actors;
    }

    @SaCheckPermission("lsmom:form:design")
    @GetMapping("/setup")
    public R<Map<String, Object>> setup(@RequestParam String formCode) {
        return R.ok(trials.setup(formCode));
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping
    public R<TrialRunner.Result> run(@RequestBody TrialRunner.Request req) {
        return R.ok(trials.run(req, actors.current()));
    }

    @SaCheckPermission("lsmom:form:design")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam String formCode) {
        ActorContext me = actors.current();
        return R.ok(trials.list(formCode, me.effectiveParty()));
    }

    @SaCheckPermission("lsmom:form:design")
    @DeleteMapping
    public R<Map<String, Object>> clear(@RequestParam String formCode) {
        ActorContext me = actors.current();
        return R.ok(Map.of("deleted", trials.clear(formCode, me.effectiveParty())));
    }
}
