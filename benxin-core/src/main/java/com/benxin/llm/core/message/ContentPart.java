package com.benxin.llm.core.message;

/**
 * 统一消息内容块。三家协议的内容结构差异（OpenAI 的字符串/数组、
 * Anthropic 的 content block、Gemini 的 parts）都在这里被抹平。
 */
public sealed interface ContentPart
        permits TextPart, ImagePart, ToolUsePart, ToolResultPart, ThinkingPart {

    /** 内容块类型标识，与协议无关。 */
    String type();

    /** 该内容块的纯文本表示；非文本块返回空串。 */
    default String asText() {
        return "";
    }

    static TextPart text(String text) {
        return new TextPart(text);
    }

    static ToolUsePart toolUse(String id, String name, String argumentsJson) {
        return new ToolUsePart(id, name, argumentsJson);
    }

    static ToolResultPart toolResult(String toolUseId, String name, String content) {
        return new ToolResultPart(toolUseId, name, content, false);
    }

    static ToolResultPart toolError(String toolUseId, String name, String content) {
        return new ToolResultPart(toolUseId, name, content, true);
    }
}