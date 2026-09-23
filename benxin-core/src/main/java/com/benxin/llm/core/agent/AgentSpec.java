package com.benxin.llm.core.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的静态描述：它是谁、用哪个模型、跑哪种 Loop、有哪些工具。
 *
 * <p>{@code @LlmAgent} 注解会被翻译成这个对象；手写 API 时也直接构造它。</p>
 */
public final class AgentSpec {

    private final String name;
    private final String model;
    private final String loop;
    private final String systemPrompt;
    private final int maxSteps;
    private final boolean stream;
    private final boolean memory;
    private final double temperature;
    private final int maxTokens;
    private final List<String> subAgents;
    private final List<String> toolNames;
    private final Map<String, Object> metadata;

    private AgentSpec(Builder b) {
        this.name = b.name;
        this.model = b.model;
        this.loop = b.loop;
        this.systemPrompt = b.systemPrompt;
        this.maxSteps = b.maxSteps;
        this.stream = b.stream;
        this.memory = b.memory;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.subAgents = Collections.unmodifiableList(new ArrayList<>(b.subAgents));
        this.toolNames = Collections.unmodifiableList(new ArrayList<>(b.toolNames));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.name = name;
        b.model = model;
        b.loop = loop;
        b.systemPrompt = systemPrompt;
        b.maxSteps = maxSteps;
        b.stream = stream;
        b.memory = memory;
        b.temperature = temperature;
        b.maxTokens = maxTokens;
        b.subAgents.addAll(subAgents);
        b.toolNames.addAll(toolNames);
        b.metadata.putAll(metadata);
        return b;
    }

    public String name() {
        return name;
    }

    public String model() {
        return model;
    }

    public String loop() {
        return loop;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public boolean stream() {
        return stream;
    }

    public boolean memory() {
        return memory;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public List<String> subAgents() {
        return subAgents;
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "AgentSpec{" + name + ", model=" + model + ", loop=" + loop + ", maxSteps=" + maxSteps + "}";
    }

    public static final class Builder {
        private String name = "agent";
        private String model;
        private String loop;
        private String systemPrompt;
        private int maxSteps = 24;
        private boolean stream;
        private boolean memory = true;
        private double temperature = -1;
        private int maxTokens = -1;
        private final List<String> subAgents = new ArrayList<>();
        private final List<String> toolNames = new ArrayList<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder name(String name) {
            if (name != null && !name.isBlank()) {
                this.name = name;
            }
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder loop(String loop) {
            this.loop = loop;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            if (maxSteps > 0) {
                this.maxSteps = maxSteps;
            }
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder memory(boolean memory) {
            this.memory = memory;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder subAgent(String name) {
            if (name != null && !name.isBlank()) {
                this.subAgents.add(name);
            }
            return this;
        }

        public Builder subAgents(List<String> names) {
            if (names != null) {
                names.forEach(this::subAgent);
            }
            return this;
        }

        public Builder toolNames(List<String> names) {
            this.toolNames.clear();
            if (names != null) {
                this.toolNames.addAll(names);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> values) {
            if (values != null) {
                this.metadata.putAll(values);
            }
            return this;
        }

        public AgentSpec build() {
            return new AgentSpec(this);
        }
    }
}