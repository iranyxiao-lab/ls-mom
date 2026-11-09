package com.lsmom.core.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * 链路标识（trace_id）上下文。
 * <p>
 * 请求、Operation、流程回调、Outbox 投递和 Agent 调用都通过这里读取同一个 trace_id，
 * 并写入日志 MDC（键 {@value #MDC_KEY}）。
 */
public final class TraceContext {

    /** 日志 MDC 键。 */
    public static final String MDC_KEY = "traceId";
    /** 请求与响应头。 */
    public static final String HEADER = "X-Trace-Id";

    /** 接受外部传入的 trace_id 时的格式限制，防止日志注入。 */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private TraceContext() {
    }

    /** 新生成一个 32 位十六进制 trace_id。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 外部传入值合法则沿用，否则新生成。 */
    public static String acceptOrCreate(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : newTraceId();
    }

    /** 当前线程的 trace_id；没有时返回 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 当前线程的 trace_id；没有时新生成并设置。 */
    public static String currentOrCreate() {
        String id = current();
        if (id == null) {
            id = newTraceId();
            set(id);
        }
        return id;
    }

    public static void set(String traceId) {
        if (traceId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /** 以指定 trace_id 执行任务，结束后恢复原 MDC（Outbox Worker 按记录中的 trace_id 投递时使用）。 */
    public static void runWith(String traceId, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            set(traceId);
            task.run();
        } finally {
            restore(previous);
        }
    }

    /** 捕获调用方的 MDC，在其他线程执行时还原（自建线程池提交任务时使用）。 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restore(captured);
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static <V> Callable<V> wrap(Callable<V> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restore(captured);
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
