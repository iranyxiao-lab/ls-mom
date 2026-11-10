package com.lsmom.ui.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 列表保存视图：查看本人可用的视图、保存、修改、删除、设为默认；共享给角色需要 lsmom:ui:view:share。 */
@RestController
@RequestMapping("/api/ui/saved-views")
public class SavedViewController {

    static final String SHARE = "lsmom:ui:view:share";
    private final SavedViewService views;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public SavedViewController(SavedViewService views, ActorResolver actors, JdbcTemplate jdbc) {
        this.views = views;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    public record Body(String concept, String name, JsonNode definition, String sharedRole) {
    }

    public record DefaultBody(String concept, Long viewId) {
    }

    private List<String> roles(long userId) {
        return jdbc.queryForList("""
            select r.role_key from sys_user_role ur join sys_role r on r.role_id = ur.role_id
             where ur.user_id = ? and r.status = '0' and r.del_flag = '0'""", String.class, userId);
    }

    /** 只能为自己有列表权限的台账保存视图。 */
    private ActorContext reader(String concept) {
        ActorContext a = actors.current();
        if (concept == null || !a.hasPermission("crud:" + concept + ":list")) throw new LsBizException("ACCESS_DENIED", "没有查看该台账的权限");
        return a;
    }

    @GetMapping
    public R<SavedViewService.Views> list(@RequestParam String concept) {
        ActorContext a = reader(concept);
        return R.ok(views.list(a.userId(), roles(a.userId()), concept));
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Body b) {
        ActorContext a = reader(b.concept());
        return R.ok(Map.of("id", views.create(a.userId(), b.concept(), b.name(), b.definition(), b.sharedRole(), a.hasPermission(SHARE))));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Body b) {
        ActorContext a = actors.current();
        views.update(a.userId(), id, b.name(), b.definition(), b.sharedRole(), a.hasPermission(SHARE));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        views.delete(actors.current().userId(), id);
        return R.ok();
    }

    /** 可共享给的角色（不含超级管理员） */
    @GetMapping("/roles")
    public R<List<Map<String, Object>>> roles() {
        if (!actors.current().hasPermission(SHARE)) throw new LsBizException("ACCESS_DENIED", "没有把视图共享给角色的权限");
        return R.ok(jdbc.queryForList("""
            select role_key as "roleKey", role_name as "roleName" from sys_role
             where status = '0' and del_flag = '0' and role_key <> 'superadmin' order by role_sort, role_id"""));
    }

    @PutMapping("/default")
    public R<Void> setDefault(@RequestBody DefaultBody b) {
        ActorContext a = reader(b.concept());
        views.setDefault(a.userId(), roles(a.userId()), b.concept(), b.viewId());
        return R.ok();
    }
}
