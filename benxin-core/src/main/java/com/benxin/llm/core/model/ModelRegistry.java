package com.benxin.llm.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型注册表：按名字管理所有可用模型，并持有"默认模型"。
 *
 * <p>{@code @LlmAgent(model = "xxx")} 里的名字就是在这里查的。
 * 该类型本身也是可插拔的 bean。</p>
 */
public class ModelRegistry {

    /**
     * 注册顺序即"回退顺序"。
     *
     * <p>刻意用 {@link LinkedHashMap} 而不是 {@code ConcurrentHashMap}：多模型且没有默认项时，
     * "第一个可用模型"必须是一个稳定、可预期的结果，否则同一份配置在不同 JVM 上会选到不同模型。
     * 读写都加了同步（注册只发生在启动期，读取也不在热路径上）。</p>
     */
    private final Map<String, LlmModel> models = new LinkedHashMap<>();
    private volatile String defaultName;

    public synchronized void register(LlmModel model) {
        register(model.name(), model);
    }

    public synchronized void register(String name, LlmModel model) {
        if (name == null || name.isBlank() || model == null) {
            return;
        }
        models.put(name, model);
    }

    public synchronized void registerAll(Map<String, LlmModel> more) {
        if (more != null) {
            more.forEach(this::register);
        }
    }

    public synchronized Optional<LlmModel> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(models.get(name));
    }

    public LlmModel get(String name) {
        return find(name).orElseThrow(() -> new ModelException(
                "未找到模型 [" + name + "]，已注册: " + names()));
    }

    public synchronized Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(models.keySet()));
    }

    public synchronized List<LlmModel> all() {
        return List.copyOf(models.values());
    }

    public synchronized boolean isEmpty() {
        return models.isEmpty();
    }

    public void setDefault(String name) {
        this.defaultName = name;
    }

    /**
     * 配置里原样设置的名字，<b>不做任何回退</b>。
     *
     * <p>用于诊断"配了一个根本取不到的名字"：{@link #defaultName()} 会回退到实际生效的名字，
     * 拿它去和 {@link #names()} 比对永远比不出问题。</p>
     */
    public String configuredDefaultName() {
        return defaultName;
    }

    /**
     * 实际生效的默认模型名。
     *
     * <p>与 {@link #defaultModel()} 口径一致：配置的名字不在注册表里时，回退到第一个可用模型。
     * 以前这个方法原样返回配置值，于是 {@code defaultName()} 可能给出一个根本取不到的名字，
     * 而 {@code defaultModel()} 却能正常工作 —— 用它做展示或日志会看到与实际使用不一致的模型名。</p>
     */
    public synchronized String defaultName() {
        if (defaultName != null && models.containsKey(defaultName)) {
            return defaultName;
        }
        String fallback = firstRegisteredName();
        return fallback != null ? fallback : defaultName;
    }

    /** 取默认模型：显式设置的优先，否则取按注册顺序的第一个。 */
    public synchronized LlmModel defaultModel() {
        if (defaultName != null && models.containsKey(defaultName)) {
            return models.get(defaultName);
        }
        if (models.isEmpty()) {
            throw new ModelException("尚未注册任何模型，请在 llm.models.* 中配置，或提供 LlmModel bean");
        }
        return models.values().iterator().next();
    }

    private String firstRegisteredName() {
        return models.isEmpty() ? null : models.keySet().iterator().next();
    }

    public synchronized void clear() {
        models.clear();
    }
}