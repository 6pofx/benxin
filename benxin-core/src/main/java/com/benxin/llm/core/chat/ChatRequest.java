package com.benxin.llm.core.chat;

import com.benxin.llm.core.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的模型调用请求。所有协议特有的字段放在 {@link #extra()} 里透传，
 * 因此新增协议特性无需改动本类。
 */
public final class ChatRequest {

    private final String model;
    private final List<ChatMessage> messages;
    private final List<ToolSpec> tools;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final List<String> stop;
    private final String toolChoice;
    private final boolean stream;
    private final Map<String, Object> extra;

    private ChatRequest(Builder b) {
        this.model = b.model;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.tools = Collections.unmodifiableList(new ArrayList<>(b.tools));
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.topP = b.topP;
        this.stop = Collections.unmodifiableList(new ArrayList<>(b.stop));
        this.toolChoice = b.toolChoice;
        this.stream = b.stream;
        this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(b.extra));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.model = model;
        b.messages.addAll(messages);
        b.tools.addAll(tools);
        b.temperature = temperature;
        b.maxTokens = maxTokens;
        b.topP = topP;
        b.stop.addAll(stop);
        b.toolChoice = toolChoice;
        b.stream = stream;
        b.extra.putAll(extra);
        return b;
    }

    public String model() {
        return model;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public List<ToolSpec> tools() {
        return tools;
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Double topP() {
        return topP;
    }

    public List<String> stop() {
        return stop;
    }

    /** {@code auto} / {@code none} / {@code required} / 具体工具名，null 表示不下发。 */
    public String toolChoice() {
        return toolChoice;
    }

    public boolean stream() {
        return stream;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /** 取出所有 system 消息的文本（Anthropic / Gemini 需要把它们提到顶层）。 */
    public String systemText() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m.role() == com.benxin.llm.core.message.Role.SYSTEM) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(m.text());
            }
        }
        return sb.toString();
    }

    /** 排除 system 消息后的对话消息。 */
    public List<ChatMessage> conversation() {
        return messages.stream()
                .filter(m -> m.role() != com.benxin.llm.core.message.Role.SYSTEM)
                .toList();
    }

    public static final class Builder {
        private String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private final List<String> stop = new ArrayList<>();
        private String toolChoice;
        private boolean stream;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.clear();
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        public Builder message(ChatMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            this.tools.clear();
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop.clear();
            if (stop != null) {
                this.stop.addAll(stop);
            }
            return this;
        }

        public Builder toolChoice(String toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder extra(String key, Object value) {
            if (key != null && value != null) {
                this.extra.put(key, value);
            }
            return this;
        }

        public Builder extra(Map<String, Object> values) {
            if (values != null) {
                this.extra.putAll(values);
            }
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}