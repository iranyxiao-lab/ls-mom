package com.lsmom.action.api;

import java.util.List;

/**
 * 不可撤销动作的影响说明（执行前确认框里列出）：例如关闭问题时列出将被关闭的问题与整改工作数量。
 * 只读，不得改动数据。
 */
public interface OperationImpactProvider {

    boolean supports(String operation);

    /** 对目标对象执行该动作的影响，每条一句业务语言。 */
    List<String> describe(String operation, String targetId);
}
