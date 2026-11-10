package com.lsmom.ui.task;

import com.lsmom.core.task.BackgroundTasks;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 任务中心：本人的导入、导出、批量操作、Pack 安装任务（进度、结果、完成通知确认）。只能看到自己发起的任务。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final BackgroundTasks tasks;

    public TaskController(BackgroundTasks tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public R<List<BackgroundTasks.Task>> mine(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(tasks.mine(LoginHelper.getUserId(), limit));
    }

    @GetMapping("/{id}")
    public R<BackgroundTasks.Task> get(@PathVariable long id) {
        return R.ok(tasks.get(id, LoginHelper.getUserId()));
    }

    /** 前端已显示完成通知：之后不再重复通知。 */
    @PostMapping("/{id}/notified")
    public R<Void> notified(@PathVariable long id) {
        tasks.notified(id, LoginHelper.getUserId());
        return R.ok();
    }
}
