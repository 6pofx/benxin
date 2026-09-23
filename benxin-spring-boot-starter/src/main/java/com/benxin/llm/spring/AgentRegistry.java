package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 已装配 Agent 的注册表。
 *
 * <p>支持"惰性注册"：{@code @LlmAgent} 接口之间的互相派生（A 有子代理 B，B 又有子代理 A）
 * 会造成循环依赖，因此这里只登记 {@link Supplier}，等到真正被调用时才构建，循环自然被打破。</p>
 */
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, Supplier<Agent>> lazyAgents = new LinkedHashMap<>();
    private final Set<String> building = new LinkedHashSet<>();

    public synchronized void register(Agent agent) {
        if (agent != null && agent.name() != null) {
            agents.put(agent.name(), agent);
        }
    }

    public synchronized void registerLazy(String name, Supplier<Agent> supplier) {
        if (name != null && supplier != null) {
            lazyAgents.put(name, supplier);
        }
    }

    public synchronized Optional<Agent> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Agent agent = agents.get(name);
        if (agent != null) {
            return Optional.of(agent);
        }
        Supplier<Agent> supplier = lazyAgents.get(name);
        if (supplier == null) {
            return Optional.empty();
        }
        if (!building.add(name)) {
            throw new IllegalStateException("检测到 Agent [" + name + "] 的循环构建，请检查子代理配置");
        }
        try {
            Agent built = supplier.get();
            if (built != null) {
                agents.put(name, built);
            }
            return Optional.ofNullable(built);
        } finally {
            building.remove(name);
        }
    }

    public Agent get(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "未找到 Agent [" + name + "]，已注册: " + names()));
    }

    public synchronized Set<String> names() {
        Set<String> all = new LinkedHashSet<>(agents.keySet());
        all.addAll(lazyAgents.keySet());
        return Collections.unmodifiableSet(all);
    }

    public synchronized Map<String, Agent> resolved() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(agents));
    }

    public synchronized boolean isEmpty() {
        return agents.isEmpty() && lazyAgents.isEmpty();
    }

    /** 预构建全部惰性 Agent（可选，用于启动期暴露配置错误而不是等到第一次调用）。 */
    public synchronized void eager() {
        for (String name : Set.copyOf(lazyAgents.keySet())) {
            try {
                find(name);
            } catch (RuntimeException e) {
                log.warn("[benxin] 预构建 Agent [{}] 失败: {}", name, e.toString());
            }
        }
    }
}