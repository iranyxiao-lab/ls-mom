package com.lsmom.action.core;

import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 问题结束（验证通过关闭、驳回、取消）的影响说明。 */
@Component
public class CoreImpacts extends IssueImpact {

    public CoreImpacts(IssueService issues) {
        super(Map.of("ls.core.Issue.verifyPass", "关闭", "ls.core.Issue.reject", "驳回", "ls.core.Issue.cancel", "取消"), "问题", id -> {
            Issue i = issues.get(Long.parseLong(id));
            return new Target(i.code() + " " + i.title(), i.id());
        }, issues);
    }
}
