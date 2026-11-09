package com.lsmom.action.core;

import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStore;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ls.core.Work 状态读写。 */
@Component
public class WorkStateStore implements StateStore {

    private final WorkItemService works;

    public WorkStateStore(WorkItemService works) {
        this.works = works;
    }

    @Override
    public boolean assignedTo(String id, Long party) {
        return party != null && works.assignedTo(Long.parseLong(id), party);
    }

    @Override
    public String concept() {
        return "ls.core.Work";
    }

    @Override
    public StateSnapshot load(String id) {
        return snap(works.get(Long.parseLong(id)));
    }

    @Override
    public Map<String, Object> attributes(String id) {
        return com.lsmom.action.rule.CelValues.asMap(works.get(Long.parseLong(id)));
    }

    @Override
    public StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes) {
        return snap(works.transition(Long.parseLong(id), expectedVersion, WorkStatus.valueOf(from), WorkStatus.valueOf(to), changes));
    }

    private static StateSnapshot snap(WorkItem w) {
        return new StateSnapshot(String.valueOf(w.id()), w.status().name(), w.version(), w.orgUnitId(), w.createdByParty());
    }
}
