package com.benxin.llm.core.loop;

import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.tool.ToolCallRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loop 一次运行的产物。 */
public final class LoopResult {

    private final String loopName;
    private final String text;
    private final FinishReason finishReason;
    private final List<ChatMessage> messages;
    private final Usage usage;
    private final int steps;
    private final List<ToolCallRecord> toolCalls;
    private final boolean maxStepsReached;
    private final Map<String, Object> attributes;

    private LoopResult(Builder b) {
        this.loopName = b.loopName;
        this.text = b.text == null ? "" : b.text;
        this.finishReason = b.finishReason == null ? FinishReason.STOP : b.finishReason;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.usage = b.usage == null ? Usage.ZERO : b.usage;
        this.steps = b.steps;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(b.toolCalls));
        this.maxStepsReached = b.maxStepsReached;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LoopResult of(String text) {
        return builder().text(text).build();
    }

    public String loopName() {
        return loopName;
    }

    public String text() {
        return text;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public Usage usage() {
        return usage;
    }

    public int steps() {
        return steps;
    }

    public List<ToolCallRecord> toolCalls() {
        return toolCalls;
    }

    /** 是否因触达步数上限而中断（通常意味着任务未完成）。 */
    public boolean maxStepsReached() {
        return maxStepsReached;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public boolean isEmpty() {
        return text.isEmpty() && toolCalls.isEmpty();
    }

    public static final class Builder {
        private String loopName;
        private String text;
        private FinishReason finishReason = FinishReason.STOP;
        private final List<ChatMessage> messages = new ArrayList<>();
        private Usage usage = Usage.ZERO;
        private int steps;
        private final List<ToolCallRecord> toolCalls = new ArrayList<>();
        private boolean maxStepsReached;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public Builder loopName(String loopName) {
            this.loopName = loopName;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.clear();
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder steps(int steps) {
            this.steps = steps;
            return this;
        }

        public Builder addToolCall(ToolCallRecord record) {
            if (record != null) {
                this.toolCalls.add(record);
            }
            return this;
        }

        public Builder toolCalls(List<ToolCallRecord> records) {
            this.toolCalls.clear();
            if (records != null) {
                this.toolCalls.addAll(records);
            }
            return this;
        }

        public Builder maxStepsReached(boolean v) {
            this.maxStepsReached = v;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public LoopResult build() {
            return new LoopResult(this);
        }
    }
}