package com.benxin.llm.core.loop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Loop 注册表。既支持单例实例，也支持 {@link Supplier} 工厂
 * （Loop 若持有每次运行的状态，用工厂可以保证并发安全）。
 */
public class LoopRegistry {

    private static class Entry {
        final AgentLoop singleton;
        final Supplier<AgentLoop> factory;
        final String description;

        Entry(AgentLoop singleton, Supplier<AgentLoop> factory, String description) {
            this.singleton = singleton;
            this.factory = factory;
            this.description = description;
        }

        AgentLoop get() {
            return singleton != null ? singleton : factory.get();
        }
    }

    private final Map<String, Entry> loops = new LinkedHashMap<>();
    private volatile String defaultName;

    public void register(AgentLoop loop) {
        if (loop != null) {
            register(loop.name(), loop);
        }
    }

    public void register(String name, AgentLoop loop) {
        if (name != null && loop != null) {
            loops.put(name, new Entry(loop, null, loop.description()));
        }
    }

    public void register(String name, Supplier<AgentLoop> factory, String description) {
        if (name != null && factory != null) {
            loops.put(name, new Entry(null, factory, description));
        }
    }

    public Optional<AgentLoop> find(String name) {
        Entry entry = name == null ? null : loops.get(name);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.get());
    }

    public AgentLoop get(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "未找到 Loop [" + name + "]，已注册: " + loops.keySet()));
    }

    public boolean contains(String name) {
        return name != null && loops.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(loops.keySet());
    }

    public List<String> descriptions() {
        return loops.entrySet().stream()
                .map(e -> e.getKey() + " —— " + (e.getValue().description == null ? "" : e.getValue().description))
                .toList();
    }

    public boolean isEmpty() {
        return loops.isEmpty();
    }

    public void setDefault(String name) {
        this.defaultName = name;
    }

    public String defaultName() {
        return defaultName;
    }

    /** 取默认 Loop：显式指定优先，否则取注册的第一个。 */
    public AgentLoop defaultLoop() {
        if (defaultName != null && loops.containsKey(defaultName)) {
            return loops.get(defaultName).get();
        }
        if (loops.isEmpty()) {
            throw new IllegalStateException("尚未注册任何 Agent Loop");
        }
        return loops.values().iterator().next().get();
    }
}