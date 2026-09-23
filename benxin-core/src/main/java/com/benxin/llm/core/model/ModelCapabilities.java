package com.benxin.llm.core.model;

/** 模型能力声明，供 Loop 决定降级策略（例如不支持工具时改用文本协议）。 */
public record ModelCapabilities(
        boolean streaming,
        boolean toolCalling,
        boolean parallelToolCalls,
        boolean vision,
        boolean thinking,
        int maxContextTokens) {

    public static final ModelCapabilities DEFAULT =
            new ModelCapabilities(true, true, false, false, false, 128_000);

    public static ModelCapabilities textOnly() {
        return new ModelCapabilities(false, false, false, false, false, 32_000);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean streaming = true;
        private boolean toolCalling = true;
        private boolean parallelToolCalls;
        private boolean vision;
        private boolean thinking;
        private int maxContextTokens = 128_000;

        public Builder streaming(boolean v) {
            this.streaming = v;
            return this;
        }

        public Builder toolCalling(boolean v) {
            this.toolCalling = v;
            return this;
        }

        public Builder parallelToolCalls(boolean v) {
            this.parallelToolCalls = v;
            return this;
        }

        public Builder vision(boolean v) {
            this.vision = v;
            return this;
        }

        public Builder thinking(boolean v) {
            this.thinking = v;
            return this;
        }

        public Builder maxContextTokens(int v) {
            this.maxContextTokens = v;
            return this;
        }

        public ModelCapabilities build() {
            return new ModelCapabilities(streaming, toolCalling, parallelToolCalls, vision, thinking,
                    maxContextTokens);
        }
    }
}