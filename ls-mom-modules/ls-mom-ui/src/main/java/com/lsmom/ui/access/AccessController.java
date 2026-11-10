package com.lsmom.ui.access;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 错误页使用：地址是否为系统页面、需要什么权限；申请访问权限。 */
@RestController
@RequestMapping("/api/ui/access")
public class AccessController {

    private final AccessService access;
    private final ActorResolver actors;

    public AccessController(AccessService access, ActorResolver actors) {
        this.access = access;
        this.actors = actors;
    }

    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam String path) {
        return R.ok(access.page(path).map(AccessService::view).orElse(null));
    }

    public record Request(String path, String reason) {
    }

    @PostMapping("/request")
    public R<Map<String, Object>> request(@RequestBody Request body) {
        ActorContext actor = actors.current();
        String reason = body.reason() == null ? null : body.reason().length() > 200 ? body.reason().substring(0, 200) : body.reason();
        boolean sent = access.request(actor.effectiveParty(), actor.displayName(), body.path(), reason);
        return R.ok(Map.of("sent", sent));
    }
}
