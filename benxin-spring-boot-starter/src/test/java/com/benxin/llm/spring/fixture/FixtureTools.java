package com.benxin.llm.spring.fixture;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** 测试用工具集，用来验证 {@code @LlmTool} bean 会被自动扫描并装配给 {@code @LlmAgent(tools = ...)}。 */
@Component
public class FixtureTools {

    private final AtomicInteger upperCalls = new AtomicInteger();

    @LlmTool(name = "upper", description = "把文本转成大写")
    public String upper(@LlmToolParam(value = "text", description = "待转换的文本") String text) {
        upperCalls.incrementAndGet();
        return text.toUpperCase();
    }

    @LlmTool(name = "never_used", description = "永远不会被调用，用于验证工具集隔离")
    public String neverUsed() {
        return "不该被调用";
    }

    public int upperCalls() {
        return upperCalls.get();
    }
}