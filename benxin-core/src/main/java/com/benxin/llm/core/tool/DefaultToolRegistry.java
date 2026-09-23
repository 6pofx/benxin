package com.benxin.llm.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** 默认工具注册表：保持插入顺序，后注册者覆盖同名工具。 */
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();

    @Override
    public synchronized void register(ToolCallback callback) {
        if (callback != null && callback.name() != null) {
            tools.put(callback.name(), callback);
        }
    }

    @Override
    public synchronized Optional<ToolCallback> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public synchronized List<ToolCallback> all() {
        return List.copyOf(tools.values());
    }

    @Override
    public synchronized Set<String> names() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(tools.keySet()));
    }

    @Override
    public ToolRegistry filtered(Predicate<ToolCallback> predicate) {
        return ToolRegistry.of(all().stream().filter(predicate).toList());
    }

    @Override
    public String toString() {
        return "ToolRegistry" + names();
    }
}