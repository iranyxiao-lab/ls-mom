package com.lsmom.ui.access;

import com.lsmom.core.party.Party;
import com.lsmom.flow.notice.Notices;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 3.8：无权限页面显示缺少的权限；申请权限通知超级管理员，24 小时内不重复。 */
class AccessServiceTest {

    private final CoreFixture core = new CoreFixture();
    private final AccessService access = new AccessService(core.jdbc, new Notices(core.jdbc));

    @Test
    void 按地址查页面与所需权限() {
        AccessService.Page p = access.page("/system/user?pageNum=1").orElseThrow();
        assertThat(p.title()).isEqualTo("用户管理");
        assertThat(p.trail()).isEqualTo("系统管理 / 用户管理");
        assertThat(p.permission()).isEqualTo("system:user:list");
        assertThat(access.page("/system/user/")).isPresent();
        assertThat(access.page("/no/such/page")).isEmpty();
        assertThat(access.page(null)).isEmpty();
    }

    @Test
    void 申请权限通知超级管理员_一天内不重复() {
        long adminUser = PgTestSupport.insertUser(PgTestSupport.uniq("adm"), "管理员", null);
        core.jdbc.update("insert into sys_user_role (user_id, role_id) values (?, 1)", adminUser);
        Party admin = core.parties.requireByUserId(adminUser);
        Party me = core.employee("申请人", null);

        assertThat(access.request(me.id(), "申请人", "/system/user", "需要维护班组成员")).isTrue();
        String msg = core.jdbc.queryForObject("select message from biz_flow_notice where biz_type = ? and biz_id = ? and party_id = ?", String.class,
            AccessService.BIZ_TYPE, me.id() + ":/system/user", admin.id());
        assertThat(msg).contains("申请人").contains("系统管理 / 用户管理").contains("system:user:list").contains("需要维护班组成员");
        assertThat(access.request(me.id(), "申请人", "/system/user", null)).as("24 小时内重复申请不再通知").isFalse();
        assertThatThrownBy(() -> access.request(me.id(), "申请人", "/no/such", null)).hasMessageContaining("页面不存在");
    }
}
