package com.benxin.llm.core.tool;

import com.benxin.llm.core.chat.ToolSpec;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** 工具注册表。可整体替换（例如换成从数据库或 MCP 服务端动态拉取的实现）。 */
public interface ToolRegistry {

    void register(ToolCallback callback);

    default void registerAll(Iterable<ToolCallback> callbacks) {
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                register(callback);
            }
        }
    }

    Optional<ToolCallback> find(String name);

    List<ToolCallback> all();

    Set<String> names();

    /** 按条件裁剪出一个子注册表，用于给不同 Agent 装配不同工具集。 */
    ToolRegistry filtered(Predicate<ToolCallback> predicate);

    /** 暴露给模型的工具声明列表。 */
    default List<ToolSpec> specs() {
        return all().stream().map(ToolCallback::spec).toList();
    }

    default boolean isEmpty() {
        return all().isEmpty();
    }

    default int size() {
        return all().size();
    }

    static ToolRegistry empty() {
        return new DefaultToolRegistry();
    }

    /** 叠加多个注册表为一个只读视图。 */
    static ToolRegistry composite(ToolRegistry... registries) {
        return new ToolRegistry() {
            @Override
            public void register(ToolCallback callback) {
                throw new UnsupportedOperationException("composite 注册表为只读视图");
            }

            @Override
            public Optional<ToolCallback> find(String name) {
                for (ToolRegistry registry : registries) {
                    Optional<ToolCallback> found = registry.find(name);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                return Optional.empty();
            }

            @Override
            public List<ToolCallback> all() {
                java.util.LinkedHashMap<String, ToolCallback> merged = new java.util.LinkedHashMap<>();
                for (ToolRegistry registry : registries) {
                    registry.all().forEach(c -> merged.put(c.name(), c));
                }
                return List.copyOf(merged.values());
            }

            @Override
            public Set<String> names() {
                return all().stream().map(ToolCallback::name).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            }

            @Override
            public ToolRegistry filtered(Predicate<ToolCallback> predicate) {
                return ToolRegistry.of(all().stream().filter(predicate).toList());
            }
        };
    }

    static ToolRegistry of(List<ToolCallback> callbacks) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerAll(callbacks);
        return registry;
    }
}