package com.benxin.llm.core.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 暴露给模型的工具声明。
 *
 * @param name        工具名
 * @param description 工具描述
 * @param inputSchema JSON Schema 形式的参数定义（对象类型）
 * @param returnDirect 结果是否直接作为最终答案
 */
public record ToolSpec(String name, String description, JsonNode inputSchema, boolean returnDirect) {

    public ToolSpec(String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, false);
    }

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
    }
}