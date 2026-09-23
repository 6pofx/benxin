package com.benxin.llm.core.tool;

import com.benxin.llm.core.support.SampleTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code @LlmTool} 扫描与反射调用：这是"写好的方法交给模型"的关键一环。 */
class ToolScannerTest {

    private final SampleTools.Annotated bean = new SampleTools.Annotated();

    @Test
    @DisplayName("只收集标注了 @LlmTool 且返回值合法的方法")
    void scansAnnotatedMethods() {
        List<String> names = SampleTools.namesOf(ToolScanner.scan(bean));

        assertThat(names).contains("search", "structured", "boom", "with_context");
        // 无返回值的方法不是有效工具
        assertThat(names).doesNotContain("silent");
        // enabled = false 的方法应被跳过
        assertThat(names).doesNotContain("hidden");
        // 未标注的方法不应被收集
        assertThat(names).doesNotContain("notATool");
    }

    @Test
    @DisplayName("注解缺省时用方法名作为工具名")
    void usesMethodNameAsFallback() {
        assertThat(SampleTools.namesOf(ToolScanner.scan(bean))).contains("structured");
    }

    @Test
    @DisplayName("参数绑定：类型转换、默认值与框架注入")
    void bindsArguments() {
        ToolCallback search = ToolScanner.scan(bean).stream()
                .filter(t -> t.name().equals("search")).findFirst().orElseThrow();

        ToolResult result = search.call(Map.of("keyword", "本心"), null);
        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("本心").contains("3");  // limit 用注解默认值

        ToolResult explicit = search.call(Map.of("keyword", "本心", "limit", 10), null);
        assertThat(explicit.content()).contains("10");
        assertThat(bean.searchCalls).hasValue(2);
    }

    @Test
    @DisplayName("ToolContext 参数由框架注入，不暴露给模型")
    void injectsToolContext() {
        ToolCallback callback = ToolScanner.scan(bean).stream()
                .filter(t -> t.name().equals("with_context")).findFirst().orElseThrow();

        assertThat(callback.spec().inputSchema().get("properties").has("context")).isFalse();
        assertThat(callback.spec().inputSchema().get("properties").has("text")).isTrue();

        ToolContext context = new ToolContext() {
            @Override public String agentName() { return "tester"; }
            @Override public String sessionId() { return "s1"; }
            @Override public int step() { return 1; }
            @Override public Map<String, Object> attributes() { return new java.util.HashMap<>(); }
            @Override public com.benxin.llm.core.model.LlmModel model() { return null; }
            @Override public com.benxin.llm.core.sandbox.ToolSandbox sandbox() {
                return com.benxin.llm.core.sandbox.ToolSandbox.permissive();
            }
            @Override public com.benxin.llm.core.sandbox.ApprovalHandler approvalHandler() {
                return com.benxin.llm.core.sandbox.ApprovalHandler.autoApprove();
            }
            @Override public com.benxin.llm.core.hook.AgentListener listener() {
                return com.benxin.llm.core.hook.AgentListener.noop();
            }
            @Override public void emit(String type, Object payload) { }
        };

        ToolResult result = callback.call(Map.of("text", "hi"), context);
        assertThat(result.content()).isEqualTo("hi@tester");
    }

    @Test
    @DisplayName("工具方法抛出的异常被转成工具错误，而不是炸掉整条链路")
    void convertsExceptionToToolError() {
        ToolCallback boom = ToolScanner.scan(bean).stream()
                .filter(t -> t.name().equals("boom")).findFirst().orElseThrow();

        ToolResult result = boom.call(Map.of(), null);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("故意失败");
    }

    @Test
    @DisplayName("DTO 返回值被序列化成 JSON 文本")
    void serializesStructuredResult() {
        ToolCallback structured = ToolScanner.scan(bean).stream()
                .filter(t -> t.name().equals("structured")).findFirst().orElseThrow();

        ToolResult result = structured.call(Map.of(), null);
        assertThat(result.content()).contains("\"ok\"").contains("true");
    }
}