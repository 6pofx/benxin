package com.benxin.llm.core.message;

/**
 * 模型发起的一次工具调用。
 *
 * @param id            调用 id（OpenAI 的 tool_call_id / Anthropic 的 tool_use id / Gemini 由本心合成）
 * @param name          工具名
 * @param argumentsJson 参数 JSON 文本（Gemini 的 functionCall.args 会被反向序列化到这里）
 */
public record ToolUsePart(String id, String name, String argumentsJson) implements ContentPart {

    public ToolUsePart {
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    @Override
    public String type() {
        return "tool_use";
    }

    @Override
    public String asText() {
        return "[" + name + "(" + argumentsJson + ")]";
    }
}