package com.lsmom.action.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.external.ExternalSyncService;
import com.lsmom.core.document.AccessAuditService;
import com.lsmom.core.event.OutboxWorker;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 失败任务与人工补偿：投递失败达上限的事件、外部同步失败记录、手工重投。 */
@RestController
@RequestMapping("/api/compensation")
public class CompensationController {

    private final OutboxWorker outbox;
    private final ExternalSyncService sync;
    private final AccessAuditService audit;
    private final ActorResolver actors;

    public CompensationController(OutboxWorker outbox, ExternalSyncService sync, AccessAuditService audit, ActorResolver actors) {
        this.outbox = outbox;
        this.sync = sync;
        this.audit = audit;
        this.actors = actors;
    }

    @SaCheckPermission("lsmom:compensation:list")
    @GetMapping("/outbox")
    public R<List<OutboxWorker.OutboxEntry>> deadLetters(@RequestParam(required = false) String destination) {
        return R.ok(outbox.deadLetters(destination));
    }

    @SaCheckPermission("lsmom:compensation:list")
    @GetMapping("/external")
    public R<List<ExternalSyncService.SyncRecord>> external(@RequestParam(required = false) String system) {
        return R.ok(sync.compensations(system));
    }

    @SaCheckPermission("lsmom:compensation:redeliver")
    @PostMapping("/outbox/{id}/redeliver")
    public R<Void> redeliver(@PathVariable long id) {
        ActorContext actor = actors.current();
        outbox.redeliver(id);
        audit.record(actor.actorParty(), "OUTBOX_REDELIVER", "OUTBOX", String.valueOf(id), true, null);
        return R.ok("已重新加入投递队列");
    }
}
