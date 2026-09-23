package com.benxin.llm.core.message;

/**
 * 工具执行结果，回灌给模型。
 *
 * @param toolUseId 对应 {@link ToolUsePart#id()}
 * @param name      工具名
 * @param content   结果文本
 * @param error     是否为错误结果
 */
public record ToolResultPart(String toolUseId, String name, String content, boolean error)
        implements ContentPart {

    public ToolResultPart {
        content = content == null ? "" : content;
    }

    @Override
    public String type() {
        return "tool_result";
    }

    @Override
    public String asText() {
        return content;
    }
}