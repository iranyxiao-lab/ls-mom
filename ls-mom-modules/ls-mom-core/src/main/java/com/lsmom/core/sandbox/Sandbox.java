package com.lsmom.core.sandbox;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 沙箱试运行标记：在只回滚的事务里完整执行业务与流程（设计器试运行）。沙箱内：
 * 独立事务（REQUIRES_NEW）改为加入当前事务、提交后回调立即执行——所有写入随沙箱事务一起回滚，正式库不留记录。
 */
public final class Sandbox {

    private static final ThreadLocal<String> RUN = new ThreadLocal<>();

    private Sandbox() {
    }

    public static boolean active() {
        return RUN.get() != null;
    }

    public static String runId() {
        return RUN.get();
    }

    public static <T> T run(String id, Supplier<T> body) {
        String prev = RUN.get();
        RUN.set(id);
        try {
            return body.get();
        } finally {
            if (prev == null) RUN.remove();
            else RUN.set(prev);
        }
    }

    /** 沙箱内用加入当前事务的模板代替独立事务。 */
    public static TransactionTemplate independent(TransactionTemplate joined, TransactionTemplate requiresNew) {
        return active() ? joined : requiresNew;
    }
}
