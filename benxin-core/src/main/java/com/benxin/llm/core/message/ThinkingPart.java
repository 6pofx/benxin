package com.benxin.llm.core.message;

/**
 * 思维链内容块。Anthropic 的 extended thinking 返回 signature，
 * DeepSeek-R1 的 reasoning_content、Gemini 的 thought 都归一到这里。
 *
 * <p>多轮回灌时部分协议要求原样带回（尤其 Anthropic），故保留 signature。</p>
 */
public record ThinkingPart(String text, String signature) implements ContentPart {

    public ThinkingPart(String text) {
        this(text, null);
    }

    @Override
    public String type() {
        return "thinking";
    }

    @Override
    public String asText() {
        return text == null ? "" : text;
    }
}