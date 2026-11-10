package com.lsmom.ui.draft;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 当前用户的表单草稿。 */
@RestController
@RequestMapping("/api/ui/drafts")
public class DraftController {

    private final DraftService drafts;

    public DraftController(DraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public R<List<Map<String, Object>>> mine() {
        return R.ok(drafts.mine(LoginHelper.getUserId()));
    }

    @GetMapping("/{key}")
    public R<DraftService.Draft> get(@PathVariable String key) {
        return R.ok(drafts.get(LoginHelper.getUserId(), key).orElse(null));
    }

    @PutMapping("/{key}")
    public R<DraftService.Draft> save(@PathVariable String key, @RequestBody JsonNode body) {
        return R.ok(drafts.save(LoginHelper.getUserId(), key, body.get("content"), body.path("hash").asText(null)));
    }

    @DeleteMapping("/{key}")
    public R<Void> delete(@PathVariable String key) {
        drafts.delete(LoginHelper.getUserId(), key);
        return R.ok();
    }
}
