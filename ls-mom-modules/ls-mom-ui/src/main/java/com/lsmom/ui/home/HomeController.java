package com.lsmom.ui.home;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 首页卡片布局：本人布局读取、调整、恢复默认；管理员设置角色默认布局。 */
@RestController
@RequestMapping("/api/ui/home")
public class HomeController {

    private final HomeService home;
    private final JdbcTemplate jdbc;

    public HomeController(HomeService home, JdbcTemplate jdbc) {
        this.home = home;
        this.jdbc = jdbc;
    }

    /** 当前用户的角色键（按角色排序，第一个有默认布局的角色生效）。 */
    private List<String> roleKeys(long userId) {
        return jdbc.queryForList("""
            select r.role_key from sys_user_role ur join sys_role r on r.role_id = ur.role_id
             where ur.user_id = ? and r.status = '0' and r.del_flag = '0' order by r.role_sort, r.role_id""", String.class, userId);
    }

    @GetMapping
    public R<HomeService.Layout> get() {
        long uid = LoginHelper.getUserId();
        return R.ok(home.layout(uid, roleKeys(uid)));
    }

    @PutMapping("/mine")
    public R<Void> saveMine(@RequestBody JsonNode body) {
        home.saveMine(LoginHelper.getUserId(), body);
        return R.ok();
    }

    @DeleteMapping("/mine")
    public R<Void> resetMine() {
        home.resetMine(LoginHelper.getUserId());
        return R.ok();
    }

    /** 可设置首页的角色（不含超级管理员）。 */
    @SaCheckPermission("lsmom:ui:home:manage")
    @GetMapping("/roles")
    public R<List<Map<String, Object>>> roles() {
        return R.ok(jdbc.queryForList("""
            select role_key as "roleKey", role_name as "roleName" from sys_role
             where status = '0' and del_flag = '0' and role_key <> 'superadmin' order by role_sort, role_id"""));
    }

    @SaCheckPermission("lsmom:ui:home:manage")
    @PutMapping("/roles/{roleKey}")
    public R<Map<String, Object>> saveRole(@PathVariable String roleKey, @RequestBody JsonNode body) {
        LoginUser u = LoginHelper.getLoginUser();
        return R.ok(Map.of("version", home.saveRole(roleKey, body, u == null ? null : u.getUserId())));
    }
}
