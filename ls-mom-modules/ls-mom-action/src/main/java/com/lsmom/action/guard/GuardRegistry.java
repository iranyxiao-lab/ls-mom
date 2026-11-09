package com.lsmom.action.guard;

import com.lsmom.action.api.OperationContext;
import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守卫注册表。Java 守卫以 Bean 形式注册；规则引擎（CEL、决策表）通过 {@link GuardProvider} 按编码解析。
 */
@Service
public class GuardRegistry {

    /** 按编码前缀（如 {@code cel:}、{@code rule:}）动态解析守卫。 */
    public interface GuardProvider {
        boolean supports(String code);

        Guard resolve(String code);

        /** 注册表创建后回调，提供者可借此委托已注册的代码守卫。 */
        default void bind(GuardRegistry registry) {
        }
    }

    /** 不修改 Operation 定义、按 Operation 编码附加守卫（如 Pack 规则声明 appliesTo）。 */
    public interface GuardAttachment {
        List<String> guardsFor(String operationCode);
    }

    private final Map<String, Guard> guards = new ConcurrentHashMap<>();
    private final List<GuardProvider> providers;
    private final List<GuardAttachment> attachments;

    public GuardRegistry(List<Guard> beans, List<GuardProvider> providers) {
        this(beans, providers, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GuardRegistry(List<Guard> beans, List<GuardProvider> providers, List<GuardAttachment> attachments) {
        beans.forEach(this::register);
        this.providers = providers;
        this.attachments = attachments;
        providers.forEach(p -> p.bind(this));
    }

    /** 附加到该 Operation 的守卫编码。 */
    public List<String> attachedFor(String operationCode) {
        List<String> out = new java.util.ArrayList<>();
        for (GuardAttachment a : attachments) a.guardsFor(operationCode).stream().filter(c -> !out.contains(c)).forEach(out::add);
        return out;
    }

    /** 已注册的 Java 守卫（不含提供者动态解析的守卫）。 */
    public java.util.Optional<Guard> registered(String code) {
        return java.util.Optional.ofNullable(guards.get(code));
    }

    public void register(Guard g) {
        if (guards.putIfAbsent(g.code(), g) != null && guards.get(g.code()) != g) {
            throw new IllegalStateException("守卫编码重复：" + g.code());
        }
    }

    public Guard resolve(String code) {
        Guard g = guards.get(code);
        if (g != null) return g;
        for (GuardProvider p : providers) {
            if (p.supports(code)) return p.resolve(code);
        }
        throw new LsBizException("GUARD_NOT_FOUND", "守卫未注册：" + code, Map.of("guard", code));
    }

    /** 依次执行守卫：WARN 记为提示，BLOCK 立即抛出 GUARD_FAILED。 */
    public void runAll(List<String> codes, OperationContext ctx) {
        for (String code : codes) {
            GuardResult r = resolve(code).check(ctx);
            switch (r.outcome()) {
                case PASS -> { }
                case WARN -> ctx.warn(r.message());
                case BLOCK -> {
                    Map<String, Object> details = new LinkedHashMap<>(r.details());
                    details.put("guard", code);
                    if (r.code() != null) details.put("reason", r.code());
                    throw new LsBizException("GUARD_FAILED", r.message(), details);
                }
            }
        }
    }

    public boolean isRegistered(String code) {
        return guards.containsKey(code) || providers.stream().anyMatch(p -> p.supports(code));
    }
}
