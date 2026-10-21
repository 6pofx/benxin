package com.benxin.llm.core.workflow;

import com.benxin.llm.core.annotation.LlmNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处理器注册表：工作流里 {@code handler:} 那个字符串唯一的解析依据。
 *
 * <p>它同时是"信任边界"的落点，所以这里既要验证"能按名字取到"，也要验证
 * "取不到时那句话能不能直接照做" —— 工作流跑挂时最有用的信息往往是
 * "是不是名字写错了，以及现在到底装了哪些"。</p>
 */
class WorkflowHandlerRegistryTest {

    @LlmNode("declared")
    static class DeclaredHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("declared");
        }
    }

    /** 故意不打注解。 */
    static class AnonymousHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("anonymous");
        }
    }

    @Test
    @DisplayName("按名字登记、按名字取用")
    void registersByName() {
        WorkflowHandler handler = in -> NodeOutput.of("hi");
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry().register("greet", handler);

        assertThat(registry.contains("greet")).isTrue();
        assertThat(registry.require("greet")).isSameAs(handler);
        assertThat(registry.names()).containsExactly("greet");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("@LlmNode 上的名字是唯一契约：类名不参与解析")
    void usesAnnotationName() {
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry()
                .register(new DeclaredHandler());

        assertThat(registry.contains("declared")).isTrue();
        // 类名（或首字母小写形式）都不该被当成名字
        assertThat(registry.contains("declaredHandler")).isFalse();
        assertThat(registry.contains("DeclaredHandler")).isFalse();
    }

    @Test
    @DisplayName("缺少 @LlmNode 时报错并点出类名，而不是回落到类名")
    void rejectsHandlerWithoutAnnotation() {
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry();

        assertThatThrownBy(() -> registry.register(new AnonymousHandler()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AnonymousHandler")
                .hasMessageContaining("@LlmNode");
    }

    @Test
    @DisplayName("取不到时把已注册的名字一并说清")
    void missingHandlerListsRegisteredNames() {
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry()
                .register("a", in -> NodeOutput.none())
                .register("b", in -> NodeOutput.none());

        assertThatThrownBy(() -> registry.require("c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的工作流处理器 [c]")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("名字两端空白被忽略；空名字与 null 处理器直接拒绝")
    void validatesRegistration() {
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry()
                .register("  padded  ", in -> NodeOutput.of("x"));
        assertThat(registry.require("padded").handle(null).output()).isEqualTo("x");

        assertThatThrownBy(() -> registry.register("  ", in -> NodeOutput.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名字不能为空");
        assertThatThrownBy(() -> registry.register("x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为 null");
    }

    @Test
    @DisplayName("名字按登记顺序保留，便于错误提示稳定")
    void preservesRegistrationOrder() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.of(Map.of());
        registry.register("z", in -> NodeOutput.none());
        registry.register("a", in -> NodeOutput.none());

        assertThat(registry.names()).containsExactly("z", "a");
    }

    @Test
    @DisplayName("空表是合法的：图里没有 java 节点时不必注册任何处理器")
    void emptyRegistryIsUsable() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty();

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.names()).isEmpty();
        assertThat(registry.find("anything")).isEmpty();
    }

    @Test
    @DisplayName("同名后登记者覆盖先前的（与 LoopRegistry 一致）")
    void laterRegistrationWins() {
        WorkflowHandler first = in -> NodeOutput.of("first");
        WorkflowHandler second = in -> NodeOutput.of("second");
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry()
                .register("x", first)
                .register("x", second);

        assertThat(registry.require("x")).isSameAs(second);
        assertThat(registry.size()).isEqualTo(1);
    }
}
