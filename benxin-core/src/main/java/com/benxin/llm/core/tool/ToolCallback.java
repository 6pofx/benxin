package com.benxin.llm.core.tool;

import com.benxin.llm.core.chat.ToolSpec;

import java.util.Map;

/**
 * 工具抽象。
 *
 * <p>三种来源都归一到本接口：反射自 {@code @LlmTool} 方法、用户手写的 Java 实现、
 * 以及从 MCP / OpenAPI 桥接进来的外部工具。</p>
 */
public interface ToolCallback {

    /** 暴露给模型的声明。 */
    ToolSpec spec();

    /** 执行。实现方应当自行把异常转成 {@link ToolResult#error}，而不是抛出。 */
    ToolResult call(Map<String, Object> arguments, ToolContext context);

    default String name() {
        return spec().name();
    }

    default String description() {
        return spec().description();
    }

    /** 结果是否直接作为最终答案返回，不再交回模型。 */
    default boolean returnDirect() {
        return spec().returnDirect();
    }

    default boolean parallelSafe() {
        return true;
    }

    /**
     * 是否需要在执行前征求人工审批。
     *
     * <p>写文件、执行命令一类有副作用的工具应当返回 {@code true}；
     * 只有当 {@code ApprovalPolicy} 为 {@code ON_REQUEST}（默认）时本方法才会被查询。</p>
     */
    default boolean requiresApproval() {
        return false;
    }
}