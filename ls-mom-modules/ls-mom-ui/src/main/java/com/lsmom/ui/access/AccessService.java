package com.lsmom.ui.access;

import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.notice.Notices;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 无权限页面的说明与权限申请：
 * - 按地址查系统菜单：页面存在但当前用户没有该菜单时，告诉用户缺少哪个权限（而不是"页面不存在"）；
 * - 申请权限：通知系统管理员（同一人同一页面 24 小时内只发一次）。
 */
@Service
public class AccessService {

    public static final String BIZ_TYPE = "ls.ui.AccessRequest";
    private final JdbcTemplate jdbc;
    private final Notices notices;

    public AccessService(JdbcTemplate jdbc, Notices notices) {
        this.jdbc = jdbc;
        this.notices = notices;
    }

    public record Page(String path, String title, String trail, String permission) {
    }

    /** 地址对应的系统菜单页面（含上级目录名称）；不存在时为空。地址中的查询参数忽略。 */
    public Optional<Page> page(String path) {
        if (path == null) return Optional.empty();
        String p = path.split("[?#]")[0].replaceAll("/+$", "");
        if (p.isEmpty() || p.length() > 300) return Optional.empty();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            with recursive t(menu_id, full_path, trail, perms, menu_name) as (
                select menu_id, '/' || trim(both '/' from path), menu_name::text, perms, menu_name
                  from sys_menu where parent_id = 0 and menu_type in ('M', 'C') and status = '0' and is_frame = '1'
                union all
                select m.menu_id, t.full_path || '/' || trim(both '/' from m.path), t.trail || ' / ' || m.menu_name, m.perms, m.menu_name
                  from sys_menu m join t on m.parent_id = t.menu_id
                 where m.menu_type in ('M', 'C') and m.status = '0' and m.is_frame = '1')
            select full_path, trail, perms, menu_name from t where full_path = ? limit 1""", p);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        return Optional.of(new Page((String) r.get("full_path"), (String) r.get("menu_name"), (String) r.get("trail"), (String) r.get("perms")));
    }

    /** 申请访问某页面：通知全部超级管理员。返回是否已发出（24 小时内重复申请不再发送）。 */
    public boolean request(Long requesterParty, String requesterName, String path, String reason) {
        Page page = page(path).orElseThrow(() -> new LsBizException("NOT_FOUND", "页面不存在：" + path));
        String bizId = requesterParty + ":" + page.path();
        Integer recent = jdbc.queryForObject("""
            select count(*) from biz_flow_notice where biz_type = ? and biz_id = ? and created_at > now() - interval '24 hours'""", Integer.class,
            BIZ_TYPE, bizId);
        if (recent != null && recent > 0) return false;
        List<Long> admins = jdbc.queryForList("""
            select distinct p.id from sys_user_role ur join sys_role r on r.role_id = ur.role_id
              join sys_user u on u.user_id = ur.user_id and u.del_flag = '0' and u.status = '0'
              join core_party p on p.user_id = u.user_id and p.status = 'ACTIVE'
             where r.role_key = 'superadmin' or r.role_id = 1""", Long.class);
        String msg = requesterName + " 申请访问「" + page.trail() + "」" + (page.permission() == null || page.permission().isBlank() ? "" : "（权限 "
            + page.permission() + "）") + (reason == null || reason.isBlank() ? "" : "：" + reason.trim());
        notices.notify("SYSTEM", admins, BIZ_TYPE, bizId, "权限申请", msg, "/system/role", null, requesterParty);
        return true;
    }

    public static Map<String, Object> view(Page p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", p.path());
        m.put("title", p.title());
        m.put("trail", p.trail());
        m.put("permission", p.permission());
        return m;
    }
}
