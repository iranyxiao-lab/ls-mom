package com.lsmom.action.core;

import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStore;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ls.core.Issue 状态读写。 */
@Component
public class IssueStateStore implements StateStore {

    private final IssueService issues;

    public IssueStateStore(IssueService issues) {
        this.issues = issues;
    }

    @Override
    public String concept() {
        return "ls.core.Issue";
    }

    @Override
    public StateSnapshot load(String id) {
        return snap(issues.get(Long.parseLong(id)));
    }

    @Override
    public StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes) {
        return snap(issues.transition(Long.parseLong(id), expectedVersion, IssueStatus.valueOf(from), IssueStatus.valueOf(to), changes));
    }

    private static StateSnapshot snap(Issue i) {
        return new StateSnapshot(String.valueOf(i.id()), i.status().name(), i.version(), i.orgUnitId(), i.reporterParty());
    }
}
