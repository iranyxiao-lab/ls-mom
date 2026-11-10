package com.lsmom.pack.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板生成的台账需要的按钮权限及其授予：发起角色可新增、编辑、提交、导入导出；审批节点角色可查看并办理审批。
 * 审批流发布时会校验节点办理人具备办理权限，所以要在提交草稿之前授予（等同在系统管理里建按钮权限、勾选角色）。
 * 已授予的不重复建；新权限在用户重新登录后生效。
 */
public final class PermissionGrants {

    public record Grant(String perms, String label, List<String> roles) {
    }

    private PermissionGrants() {
    }

    /** 计算需要的授权：initiatorRoles 为发起（登记）角色，节点角色取自审批流。 */
    public static List<Grant> plan(JsonNode fragment, String ns, List<String> initiatorRoles) {
        String concept = ns + "." + fragment.path("entity").asText();
        String perm = ns.replace('.', ':');
        Set<String> nodeRoles = new LinkedHashSet<>();
        fragment.path("flow").path("nodes").forEach(n -> nodeRoles.add(n.path("role").asText("workshop_leader")));
        if (nodeRoles.isEmpty()) nodeRoles.add("workshop_leader");
        Set<String> starters = new LinkedHashSet<>(initiatorRoles == null ? List.of() : initiatorRoles);
        Set<String> viewers = new LinkedHashSet<>(starters);
        viewers.addAll(nodeRoles);
        String name = fragment.path("entityName").asText(fragment.path("entity").asText());
        List<Grant> out = new ArrayList<>();
        out.add(new Grant("crud:" + concept + ":list", name + "查看列表", List.copyOf(viewers)));
        out.add(new Grant("crud:" + concept + ":query", name + "查看详情", List.copyOf(viewers)));
        for (String[] a : new String[][]{{"add", "新增"}, {"edit", "编辑"}, {"export", "导出"}, {"import", "导入"}}) {
            out.add(new Grant("crud:" + concept + ":" + a[0], name + a[1], List.copyOf(starters)));
        }
        out.add(new Grant(perm + ":submit", name + "提交与撤回", List.copyOf(starters)));
        out.add(new Grant(perm + ":flow", name + "审批办理", List.copyOf(nodeRoles)));
        return out;
    }

    /** 建按钮权限（挂在隐藏目录"title 权限"下）并授予角色；角色不存在时报错，不做部分授予。 */
    public static synchronized List<Grant> apply(JdbcTemplate jdbc, String title, List<Grant> grants) {
        Map<String, Long> roleIds = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        for (Grant g : grants) {
            for (String r : g.roles()) {
                if (roleIds.containsKey(r)) continue;
                List<Long> id = jdbc.queryForList("select role_id from sys_role where role_key = ? and del_flag = '0'", Long.class, r);
                if (id.isEmpty()) missing.put("roles." + r, "角色不存在：" + r);
                else roleIds.put(r, id.get(0));
            }
        }
        if (!missing.isEmpty()) throw LsBizException.validation(missing);
        String dirName = title + " 权限";
        Long dir = jdbc.queryForList("select menu_id from sys_menu where menu_name = ? and parent_id = 0 and menu_type = 'M'", Long.class, dirName)
            .stream().findFirst().orElseGet(() -> insertMenu(jdbc, dirName, 0L, "M", "", "1"));
        for (Grant g : grants) {
            if (g.roles().isEmpty()) continue;
            Long menu = jdbc.queryForList("select menu_id from sys_menu where perms = ? and menu_type = 'F'", Long.class, g.perms()).stream().findFirst()
                .orElseGet(() -> insertMenu(jdbc, g.label(), dir, "F", g.perms(), "0"));
            for (String r : g.roles()) {
                jdbc.update("insert into sys_role_menu (role_id, menu_id) values (?, ?) on conflict do nothing", roleIds.get(r), menu);
            }
            // 上级目录也要勾上，否则角色菜单树回写时会丢失按钮
            for (String r : g.roles()) {
                jdbc.update("insert into sys_role_menu (role_id, menu_id) values (?, ?) on conflict do nothing", roleIds.get(r), dir);
            }
        }
        return grants;
    }

    private static long insertMenu(JdbcTemplate jdbc, String name, Long parent, String type, String perms, String visible) {
        long id = jdbc.queryForObject("select greatest(coalesce(max(menu_id), 0), 990000) + 1 from sys_menu", Long.class);
        jdbc.update("""
            insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible,
                                  status, perms, icon, create_dept, create_by, create_time)
            values (?, ?, ?, 99, ?, null, '', '1', '0', ?, ?, '0', ?, '#', 103, 1, now())""",
            id, name, parent, "M".equals(type) ? "tpl-" + id : "", type, visible, perms);
        return id;
    }
}
