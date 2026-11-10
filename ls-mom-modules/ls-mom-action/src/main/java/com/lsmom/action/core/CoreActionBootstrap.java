package com.lsmom.action.core;

import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.state.StateMachineRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动顺序：核心 Concept 自注册（100）→ 本类（200）：发布核心状态机、对账 Java Operation。
 * 对账失败抛出异常，应用启动失败。
 */
@Component
@Order(200)
public class CoreActionBootstrap implements ApplicationRunner {

    private final StateMachineRegistry machines;
    private final OperationRegistry operations;
    private final List<OperationHandler> handlers;

    public CoreActionBootstrap(StateMachineRegistry machines, OperationRegistry operations, List<OperationHandler> handlers) {
        this.machines = machines;
        this.operations = operations;
        this.handlers = handlers;
    }

    @Override
    public void run(ApplicationArguments args) {
        machines.publish(CoreStateMachines.work());
        machines.publish(CoreStateMachines.issue());
        machines.publish(CoreStateMachines.summary());
        operations.reconcile(handlers);
    }
}
