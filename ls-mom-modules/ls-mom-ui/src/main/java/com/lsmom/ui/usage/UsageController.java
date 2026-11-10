package com.lsmom.ui.usage;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 使用埋点：登录用户上报（匿名入库）；管理员查看放弃点报表、开关埋点。 */
@RestController
@RequestMapping("/api/ui/usage")
public class UsageController {

    static final String VIEW = "lsmom:ui:usage";
    static final String MANAGE = "lsmom:ui:usage:manage";

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    public record EnabledBody(boolean enabled) {
    }

    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(Map.of("enabled", usage.enabled()));
    }

    @PostMapping("/events")
    public R<Map<String, Object>> events(@RequestBody List<UsageService.Event> events) {
        return R.ok(Map.of("accepted", usage.record(events)));
    }

    @SaCheckPermission(VIEW)
    @GetMapping("/report")
    public R<List<Map<String, Object>>> report(@RequestParam(defaultValue = "30") int days) {
        return R.ok(usage.report(days));
    }

    @SaCheckPermission(MANAGE)
    @PutMapping("/config")
    public R<Map<String, Object>> setConfig(@RequestBody EnabledBody body) {
        usage.setEnabled(body.enabled(), LoginHelper.getUserId());
        return R.ok(Map.of("enabled", usage.enabled()));
    }
}
