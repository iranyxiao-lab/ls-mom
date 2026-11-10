package com.lsmom.flow.web;

import com.lsmom.action.web.ActorResolver;
import com.lsmom.flow.runtime.WorkCenter;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 协同工作中心：待办、已办、我发起的、抄送、委托、消息（消息已读不影响待办）与办理上下文。只返回当前登录人自己的数据。 */
@RestController
@RequestMapping("/api/workbench")
public class WorkCenterController {

    private final WorkCenter center;
    private final ActorResolver actors;

    public WorkCenterController(WorkCenter center, ActorResolver actors) {
        this.center = center;
        this.actors = actors;
    }

    private Long me() {
        return actors.current().effectiveParty();
    }

    private static WorkCenter.Filter filter(String bizType, String urgency, String keyword) {
        return new WorkCenter.Filter(bizType, urgency == null || urgency.isBlank() ? null : WorkCenter.Urgency.valueOf(urgency.toUpperCase()), keyword);
    }

    @GetMapping("/counts")
    public R<Map<String, Object>> counts() {
        return R.ok(center.counts(me()));
    }

    @GetMapping("/todo")
    public R<List<WorkCenter.Item>> todo(@RequestParam(required = false) String bizType, @RequestParam(required = false) String urgency,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(center.todo(me(), filter(bizType, urgency, keyword)));
    }

    @GetMapping("/done")
    public R<List<WorkCenter.Item>> done(@RequestParam(required = false) String bizType, @RequestParam(required = false) String keyword) {
        return R.ok(center.done(me(), filter(bizType, null, keyword)));
    }

    @GetMapping("/started")
    public R<List<Map<String, Object>>> started(@RequestParam(required = false) String bizType) {
        return R.ok(center.started(me(), filter(bizType, null, null)));
    }

    @GetMapping("/cc")
    public R<List<WorkCenter.Item>> cc(@RequestParam(required = false) String bizType) {
        return R.ok(center.cc(me(), filter(bizType, null, null)));
    }

    @GetMapping("/delegations")
    public R<List<Map<String, Object>>> delegations() {
        return R.ok(center.delegations(me()));
    }

    @GetMapping("/notifications")
    public R<Map<String, Object>> notifications() {
        return R.ok(center.notifications(me()));
    }

    @GetMapping("/messages")
    public R<List<Map<String, Object>>> messages(@RequestParam(required = false) Boolean unread) {
        return R.ok(center.messages(me(), unread));
    }

    public record Ids(List<Long> ids) {
    }

    @PostMapping("/messages/read")
    public R<Integer> read(@RequestBody(required = false) Ids b) {
        return R.ok(center.readMessages(me(), b == null ? null : b.ids()));
    }

    /** 转办对象选择：在职人员（只返回姓名与主组织）。 */
    @GetMapping("/parties")
    public R<List<Map<String, Object>>> parties(@RequestParam(required = false) String keyword) {
        return R.ok(center.parties(keyword));
    }

    /** 单据的流程进度：状态与下一步（当前办理人、剩余时间、本人待办、催办）与步骤条。 */
    @GetMapping("/biz/{concept}/{id}/progress")
    public R<Map<String, Object>> progress(@PathVariable String concept, @PathVariable String id) {
        return R.ok(center.progress(concept, id, actors.current()));
    }

    @GetMapping("/tasks/{taskId}/context")
    public R<Map<String, Object>> context(@PathVariable String taskId) {
        return R.ok(center.context(taskId, actors.current()));
    }
}
