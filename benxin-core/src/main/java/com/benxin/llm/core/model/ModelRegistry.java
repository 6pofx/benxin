package com.benxin.llm.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表：按名字管理所有可用模型，并持有"默认模型"。
 *
 * <p>{@code @LlmAgent(model = "xxx")} 里的名字就是在这里查的。
 * 该类型本身也是可替换的 bean。</p>
 */
public class ModelRegistry {

    private final Map<String, LlmModel> models = new ConcurrentHashMap<>();
    private volatile String defaultName;

    public void register(LlmModel model) {
        register(model.name(), model);
    }

    public void register(String name, LlmModel model) {
        if (name == null || name.isBlank() || model == null) {
            return;
        }
        models.put(name, model);
    }

    public void registerAll(Map<String, LlmModel> more) {
        if (more != null) {
            more.forEach(this::register);
        }
    }

    public Optional<LlmModel> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(models.get(name));
    }

    public LlmModel get(String name) {
        return find(name).orElseThrow(() -> new ModelException(
                "未找到模型 [" + name + "]，已注册: " + models.keySet()));
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(models.keySet());
    }

    public List<LlmModel> all() {
        return List.copyOf(models.values());
    }

    public boolean isEmpty() {
        return models.isEmpty();
    }

    public void setDefault(String name) {
        this.defaultName = name;
    }

    public String defaultName() {
        return defaultName;
    }

    /** 取默认模型：显式设置的优先，否则取唯一注册项，多个时取第一个并给出提示。 */
    public LlmModel defaultModel() {
        if (defaultName != null && models.containsKey(defaultName)) {
            return models.get(defaultName);
        }
        if (models.isEmpty()) {
            throw new ModelException("尚未注册任何模型，请在 llm.models.* 中配置，或提供 LlmModel bean");
        }
        if (models.size() == 1) {
            return models.values().iterator().next();
        }
        Map<String, LlmModel> ordered = new LinkedHashMap<>(models);
        String first = ordered.keySet().iterator().next();
        return ordered.get(first);
    }

    public void clear() {
        models.clear();
    }
}