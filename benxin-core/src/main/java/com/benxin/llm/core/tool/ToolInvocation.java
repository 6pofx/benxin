package com.benxin.llm.core.tool;

import com.benxin.llm.core.message.ToolUsePart;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的完整上下文，会贯穿沙箱校验、拦截器、审批与执行四个环节。
 */
public record ToolInvocation(
        String toolUseId,
        String toolName,
        Map<String, Object> arguments,
        String agentName,
        String sessionId,
        int step) {

    public ToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    }

    public static ToolInvocation of(ToolUsePart part, String agentName, String sessionId, int step) {
        return new ToolInvocation(part.id(), part.name(),
                com.benxin.llm.core.util.Json.toMap(
                        com.benxin.llm.core.util.Json.parseQuietly(part.argumentsJson())),
                agentName, sessionId, step);
    }

    /** 人类可读的单行摘要，用于日志与事件。 */
    public String summary() {
        return toolName + " " + com.benxin.llm.core.util.Json.writeQuietly(arguments);
    }
}