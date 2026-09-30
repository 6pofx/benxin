package com.benxin.llm.spring;

import com.benxin.llm.core.hook.AgentInterceptor;

import java.util.Comparator;
import java.util.List;

/**
 * 拦截器链的显式持有者。
 *
 * <p><b>为什么需要这么一个"只有一个 List 字段"的类型：</b>
 * {@code LlmAutoConfiguration.llmInterceptors()} 会把带 {@code @LlmGuard} 的拦截器包成
 * {@link SelectiveInterceptor} 再返回，但它的 bean 类型是 {@code List<AgentInterceptor>}。
 * 而 {@code ObjectProvider<AgentInterceptor>} 解析的是"类型为 AgentInterceptor 的 bean" ——
 * 那个 {@code List} 不是这个类型，于是消费端绕过了包装，直接拿到容器里<b>未被包装</b>的原始 bean，
 * {@code @LlmGuard(agents = {...})} 声明的范围因此完全失效（对不该生效的 Agent 也触发）。</p>
 *
 * <p>引入这个专用类型后，"已被包装的那份清单"成了一个可被依赖的 bean，
 * 注解 → supports() 这段桥接才真正接通。</p>
 */
public final class LlmInterceptorChain {

    private static final LlmInterceptorChain EMPTY = new LlmInterceptorChain(List.of());

    private final List<AgentInterceptor> interceptors;

    public LlmInterceptorChain(List<AgentInterceptor> interceptors) {
        this.interceptors = interceptors == null
                ? List.of()
                : interceptors.stream()
                        .sorted(Comparator.comparingInt(AgentInterceptor::order))
                        .toList();
    }

    /** 按 {@code order()} 升序排好的拦截器（已包含 {@code @LlmGuard} 的范围包装）。 */
    public List<AgentInterceptor> interceptors() {
        return interceptors;
    }

    public static LlmInterceptorChain empty() {
        return EMPTY;
    }

    @Override
    public String toString() {
        return "LlmInterceptorChain" + interceptors;
    }
}
