package com.lsmom.ui.pref;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录用户的界面偏好、收藏与最近访问。 */
@RestController
@RequestMapping("/api/ui/prefs")
public class PrefController {

    private final PrefService prefs;

    public PrefController(PrefService prefs) {
        this.prefs = prefs;
    }

    @GetMapping
    public R<PrefService.Prefs> get() {
        return R.ok(prefs.get(LoginHelper.getUserId()));
    }

    @PutMapping
    public R<JsonNode> update(@RequestBody JsonNode body) {
        return R.ok(prefs.updatePrefs(LoginHelper.getUserId(), body));
    }

    @PutMapping("/nav")
    public R<Void> nav(@RequestBody JsonNode body) {
        prefs.updateNav(LoginHelper.getUserId(), body);
        return R.ok();
    }
}
