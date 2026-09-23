package com.benxin.llm.core.chat;

import com.benxin.llm.core.message.ChatMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一的模型响应。 */
public final class ChatResponse {

    private final String id;
    private final String model;
    private final ChatMessage message;
    private final FinishReason finishReason;
    private final Usage usage;
    private final Map<String, Object> raw;

    private ChatResponse(Builder b) {
        this.id = b.id;
        this.model = b.model;
        this.message = b.message;
        this.finishReason = b.finishReason == null ? FinishReason.UNKNOWN : b.finishReason;
        this.usage = b.usage == null ? Usage.ZERO : b.usage;
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(b.raw));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ChatResponse of(ChatMessage message) {
        return builder().message(message).finishReason(FinishReason.STOP).build();
    }

    public String id() {
        return id;
    }

    public String model() {
        return model;
    }

    public ChatMessage message() {
        return message;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public Usage usage() {
        return usage;
    }

    /**
     * 协议相关的原始字段（例如 OpenAI 的 {@code reasoning_content}，
     * Anthropic 的 {@code stop_reason}，Gemini 的 {@code safetyRatings}）。
     */
    public Map<String, Object> raw() {
        return raw;
    }

    public String text() {
        return message == null ? "" : message.text();
    }

    public static final class Builder {
        private String id;
        private String model;
        private ChatMessage message;
        private FinishReason finishReason = FinishReason.UNKNOWN;
        private Usage usage = Usage.ZERO;
        private final Map<String, Object> raw = new LinkedHashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder message(ChatMessage message) {
            this.message = message;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder raw(Map<String, Object> raw) {
            if (raw != null) {
                this.raw.putAll(raw);
            }
            return this;
        }

        public Builder raw(String key, Object value) {
            if (key != null) {
                this.raw.put(key, value);
            }
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(this);
        }
    }
}