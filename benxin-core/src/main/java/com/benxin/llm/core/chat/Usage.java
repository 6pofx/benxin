package com.benxin.llm.core.chat;

/** 统一的 token 用量统计。 */
public record Usage(int inputTokens, int outputTokens, int cachedInputTokens, int reasoningTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cachedInputTokens + other.cachedInputTokens,
                reasoningTokens + other.reasoningTokens);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isEmpty() {
        return totalTokens() == 0;
    }
}