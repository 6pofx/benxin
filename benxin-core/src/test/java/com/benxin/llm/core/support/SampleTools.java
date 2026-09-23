package com.benxin.llm.core.support;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用的工具样例：既有注解式，也有手写式，覆盖两种扩展路径。 */
public final class SampleTools {

    private SampleTools() {
    }

    /** 注解式工具集合。 */
    public static class Annotated {

        public final AtomicInteger searchCalls = new AtomicInteger();

        @LlmTool(name = "search", description = "搜索关键词")
        public String search(@LlmToolParam(value = "keyword", description = "关键词") String keyword,
                             @LlmToolParam(value = "limit", description = "条数", required = false,
                                     defaultValue = "3") int limit) {
            searchCalls.incrementAndGet();
            return "命中 " + limit + " 条与「" + keyword + "」相关的结果";
        }

        @LlmTool(description = "返回一个结构化对象，用于验证 DTO 序列化")
        public Object structured() {
            return Json.parse("{\"ok\":true,\"count\":2}");
        }

        @LlmTool(name = "boom", description = "总是抛异常，用于验证异常被转成工具错误")
        public String boom() {
            throw new IllegalStateException("故意失败");
        }

        @LlmTool(name = "silent", description = "没有返回值，不应被注册")
        public void silent() {
            // 无返回值的方法不是有效工具
        }

        @LlmTool(name = "hidden", description = "被禁用", enabled = false)
        public String hidden() {
            return "不该出现";
        }

        @LlmTool(name = "with_context", description = "接收框架注入的 ToolContext")
        public String withContext(@LlmToolParam(value = "text") String text, ToolContext context) {
            return text + "@" + context.agentName();
        }

        public String notATool(String value) {
            return value;
        }
    }

    /** 手写式工具：直接实现 ToolCallback。 */
    public static ToolCallback echoTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "回显参数", Json.parse(
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                return ToolResult.ok("echo:" + arguments.get("text"));
            }
        };
    }

    /** 结果直接作为最终答案返回的工具。 */
    public static ToolCallback directTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "直接返回结果", Json.parse("{\"type\":\"object\"}"), true);
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                return ToolResult.ok("直接答案");
            }
        };
    }

    public static List<String> namesOf(List<ToolCallback> callbacks) {
        return callbacks.stream().map(ToolCallback::name).toList();
    }
}