package com.benxin.llm.core.agent;

import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.tool.ToolCallRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 一次调用的最终结果，同时是审计与可观测性的数据载体。 */
public final class AgentResult {

    private final String agentName;
    private final String sessionId;
    private final String loopName;
    private final String modelName;
    private final String text;
    private final FinishReason finishReason;
    private final List<ChatMessage> messages;
    private final Usage usage;
    private final int steps;
    private final List<ToolCallRecord> toolCalls;
    private final boolean maxStepsReached;
    private final long durationMillis;
    private final Map<String, Object> attributes;

    private AgentResult(Builder b) {
        this.agentName = b.agentName;
        this.sessionId = b.sessionId;
        this.loopName = b.loopName;
        this.modelName = b.modelName;
        this.text = b.text == null ? "" : b.text;
        this.finishReason = b.finishReason == null ? FinishReason.STOP : b.finishReason;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.usage = b.usage == null ? Usage.ZERO : b.usage;
        this.steps = b.steps;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(b.toolCalls));
        this.maxStepsReached = b.maxStepsReached;
        this.durationMillis = b.durationMillis;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 由 Loop 产物组装。 */
    public static AgentResult from(String agentName, String sessionId, String modelName,
                                   LoopResult loopResult, long durationMillis, AgentSpec spec) {
        LoopResult r = loopResult == null ? LoopResult.of("") : loopResult;
        return builder()
                .agentName(agentName)
                .sessionId(sessionId)
                .modelName(modelName)
                .loopName(r.loopName())
                .text(r.text())
                .finishReason(r.finishReason())
                .messages(r.messages())
                .usage(r.usage())
                .steps(r.steps())
                .toolCalls(r.toolCalls())
                .maxStepsReached(r.maxStepsReached())
                .durationMillis(durationMillis)
                .attributes(spec == null ? Map.of() : spec.metadata())
                .build();
    }

    public String agentName() {
        return agentName;
    }

    public String sessionId() {
        return sessionId;
    }

    public String loopName() {
        return loopName;
    }

    public String modelName() {
        return modelName;
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

    public boolean maxStepsReached() {
        return maxStepsReached;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return "AgentResult{" + agentName + ", " + steps + " 步, " + durationMillis + "ms, "
                + usage.inputTokens() + "/" + usage.outputTokens() + " tokens}";
    }

    public static final class Builder {
        private String agentName;
        private String sessionId;
        private String loopName;
        private String modelName;
        private String text;
        private FinishReason finishReason = FinishReason.STOP;
        private final List<ChatMessage> messages = new ArrayList<>();
        private Usage usage = Usage.ZERO;
        private int steps;
        private final List<ToolCallRecord> toolCalls = new ArrayList<>();
        private boolean maxStepsReached;
        private long durationMillis;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public Builder agentName(String v) {
            this.agentName = v;
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder loopName(String v) {
            this.loopName = v;
            return this;
        }

        public Builder modelName(String v) {
            this.modelName = v;
            return this;
        }

        public Builder text(String v) {
            this.text = v;
            return this;
        }

        public Builder finishReason(FinishReason v) {
            this.finishReason = v;
            return this;
        }

        public Builder messages(List<ChatMessage> v) {
            this.messages.clear();
            if (v != null) {
                this.messages.addAll(v);
            }
            return this;
        }

        public Builder usage(Usage v) {
            this.usage = v;
            return this;
        }

        public Builder steps(int v) {
            this.steps = v;
            return this;
        }

        public Builder toolCalls(List<ToolCallRecord> v) {
            this.toolCalls.clear();
            if (v != null) {
                this.toolCalls.addAll(v);
            }
            return this;
        }

        public Builder maxStepsReached(boolean v) {
            this.maxStepsReached = v;
            return this;
        }

        public Builder durationMillis(long v) {
            this.durationMillis = v;
            return this;
        }

        public Builder attributes(Map<String, Object> v) {
            if (v != null) {
                this.attributes.putAll(v);
            }
            return this;
        }

        public AgentResult build() {
            return new AgentResult(this);
        }
    }
}