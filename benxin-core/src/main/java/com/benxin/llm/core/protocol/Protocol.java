package com.benxin.llm.core.protocol;

/**
 * 本心支持的三种主流 API 协议格式。
 *
 * <p>之所以是"协议"而不是"厂商"：同一协议被大量厂商兼容复用，例如
 * OpenAI Chat Completions 协议同时服务 OpenAI、DeepSeek、通义千问、Kimi、
 * GLM、vLLM、Ollama、OneAPI 等。</p>
 */
public enum Protocol {

    /** {@code POST /v1/chat/completions} —— 事实上的行业标准。 */
    OPENAI("openai"),

    /** {@code POST /v1/messages} —— Anthropic Messages API。 */
    ANTHROPIC("anthropic"),

    /** {@code POST /v1beta/models/{model}:generateContent} —— Google Gemini。 */
    GEMINI("gemini");

    private final String id;

    Protocol(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Protocol from(String value) {
        if (value == null || value.isBlank()) {
            return OPENAI;
        }
        String v = value.trim().toLowerCase().replace('-', '_');
        for (Protocol p : values()) {
            if (p.id.equals(v) || p.name().equalsIgnoreCase(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知协议: " + value + "（可选: openai / anthropic / gemini）");
    }
}