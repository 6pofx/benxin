package com.benxin.llm.spring;

import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.SlidingWindowCompactor;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.spring.fixture.FixtureTools;
import com.benxin.llm.spring.fixture.StubModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置的条件装配与"用户 bean 优先"语义。
 *
 * <p>这组测试是"一切皆可替换"这个主张的直接证据：只要定义一个同类型 bean，
 * 就会接管对应组件，不需要开关、不需要排除自动配置。</p>
 */
class LlmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class));

    @Test
    @DisplayName("最小配置下所有核心组件都被装配出来")
    void wiresCoreComponents() {
        runner.withPropertyValues(
                        "llm.default-model=demo",
                        "llm.models.demo.protocol=openai",
                        "llm.models.demo.base-url=https://example.invalid",
                        "llm.models.demo.model=demo-chat")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelRegistry.class);
                    assertThat(context).hasSingleBean(LoopRegistry.class);
                    assertThat(context).hasSingleBean(MemoryStore.class);
                    assertThat(context).hasSingleBean(SystemPromptProvider.class);
                    assertThat(context).hasSingleBean(ToolSandbox.class);
                    assertThat(context).hasSingleBean(AgentRegistry.class);
                    assertThat(context).hasSingleBean(LlmAgentFactory.class);
                    assertThat(context).hasSingleBean(LlmToolCatalog.class);

                    ModelRegistry models = context.getBean(ModelRegistry.class);
                    assertThat(models.names()).contains("demo");
                    assertThat(models.defaultName()).isEqualTo("demo");
                    assertThat(models.defaultModel().name()).isEqualTo("demo");
                });
    }

    @Test
    @DisplayName("六个内置 Loop 全部注册，默认是 dsh-minimal")
    void registersBuiltinLoops() {
        runner.run(context -> {
            LoopRegistry loops = context.getBean(LoopRegistry.class);
            assertThat(loops.names()).containsExactlyInAnyOrder(
                    "dsh-minimal", "react", "claude-code", "codex", "plan-execute", "reflexion");
            assertThat(loops.defaultName()).isEqualTo("dsh-minimal");
        });
    }

    @Test
    @DisplayName("llm.agent.default-loop 能改变默认 Loop")
    void honoursConfiguredDefaultLoop() {
        runner.withPropertyValues("llm.agent.default-loop=claude-code")
                .run(context -> assertThat(context.getBean(LoopRegistry.class).defaultName())
                        .isEqualTo("claude-code"));
    }

    @Test
    @DisplayName("用户定义的 MemoryStore bean 覆盖默认实现")
    void userBeanOverridesDefault() {
        MemoryStore custom = new MemoryStore() {
            @Override public java.util.List<com.benxin.llm.core.message.ChatMessage> load(String id) {
                return java.util.List.of();
            }
            @Override public void save(String id, java.util.List<com.benxin.llm.core.message.ChatMessage> h) { }
            @Override public void clear(String id) { }
        };
        runner.withBean("customMemory", MemoryStore.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryStore.class);
                    assertThat(context.getBean(MemoryStore.class)).isSameAs(custom);
                });
    }

    @Test
    @DisplayName("用户定义的 ContextCompactor bean 覆盖默认的摘要压缩器")
    void userCompactorOverridesSummarizer() {
        ContextCompactor sliding = new SlidingWindowCompactor();
        runner.withBean("slidingCompactor", ContextCompactor.class, () -> sliding)
                .run(context -> assertThat(context.getBean(ContextCompactor.class)).isSameAs(sliding));
    }

    @Test
    @DisplayName("容器里的 LlmModel bean 会被注册进模型表")
    void registersModelBeans() {
        runner.withBean("stubModel", LlmModel.class, StubModel::new)
                .run(context -> {
                    ModelRegistry models = context.getBean(ModelRegistry.class);
                    assertThat(models.names()).contains("stub");
                    assertThat(models.defaultModel()).isInstanceOf(StubModel.class);
                });
    }

    @Test
    @DisplayName("llm.enabled=false 时整体不装配")
    void disabledByProperty() {
        runner.withPropertyValues("llm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ModelRegistry.class));
    }

    @Test
    @DisplayName("内置工具默认关闭；打开后才注册，并且沙箱不再是宽松实现")
    void builtinToolsAreOptIn() {
        runner.run(context -> assertThat(context.getBean(ToolSandbox.class))
                .isSameAs(ToolSandbox.permissive()));

        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=.",
                        "llm.tools.allow-write=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LlmToolCatalog catalog = context.getBean(LlmToolCatalog.class);
                    assertThat(catalog.global().names())
                            .contains("read", "write", "edit", "glob", "grep");
                    // 打开内置工具后沙箱必须换成真正的围栏实现
                    assertThat(context.getBean(ToolSandbox.class)).isNotSameAs(ToolSandbox.permissive());
                });
    }

    @Test
    @DisplayName("@LlmTool 方法所在的 bean 被自动扫描进工具目录")
    void scansToolBeans() {
        runner.withBean("fixtureTools", FixtureTools.class, FixtureTools::new)
                .run(context -> {
                    LlmToolCatalog catalog = context.getBean(LlmToolCatalog.class);
                    assertThat(catalog.global().names()).contains("upper", "never_used");
                    assertThat(catalog.forClasses(new Class<?>[]{FixtureTools.class}).names())
                            .containsExactlyInAnyOrder("upper", "never_used");
                });
    }

    @Test
    @DisplayName("手工注册的 ToolCallback bean 也能进目录")
    void registersManualToolBeans() {
        runner.withBean("manualTool", ToolCallback.class, () -> new ToolCallback() {
                    @Override
                    public com.benxin.llm.core.chat.ToolSpec spec() {
                        return new com.benxin.llm.core.chat.ToolSpec("manual", "手工工具",
                                com.benxin.llm.core.util.Json.parse("{\"type\":\"object\"}"));
                    }

                    @Override
                    public com.benxin.llm.core.tool.ToolResult call(
                            java.util.Map<String, Object> arguments,
                            com.benxin.llm.core.tool.ToolContext ctx) {
                        return com.benxin.llm.core.tool.ToolResult.ok("ok");
                    }
                })
                .run(context -> assertThat(context.getBean(LlmToolCatalog.class).global().names())
                        .contains("manual"));
    }

    @Test
    @DisplayName("自定义 Loop 类只要成为 bean 就会被按 @LlmLoop 名字注册")
    void registersCustomLoopBeans() {
        runner.withBean("myLoop", com.benxin.llm.core.loop.AgentLoop.class, CustomLoop::new)
                .run(context -> {
                    LoopRegistry loops = context.getBean(LoopRegistry.class);
                    assertThat(loops.contains("custom-test-loop")).isTrue();
                    assertThat(loops.get("custom-test-loop")).isInstanceOf(CustomLoop.class);
                });
    }

    @Test
    @DisplayName("没有配置任何模型时，模型表为空但不阻止启动")
    void startsWithoutModels() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelRegistry.class).isEmpty()).isTrue();
            assertThat(context.getBean(MemoryStore.class)).isInstanceOf(InMemoryMemoryStore.class);
        });
    }

    /** 自定义 Loop：验证 {@code @LlmLoop} 注解上的名字而非类名被采用。 */
    @com.benxin.llm.core.annotation.LlmLoop("custom-test-loop")
    static class CustomLoop extends com.benxin.llm.core.loop.AbstractAgentLoop {
        @Override
        public String name() {
            return "类名不是注册名";
        }

        @Override
        protected com.benxin.llm.core.loop.LoopResult doRun(
                com.benxin.llm.core.loop.LoopContext ctx) {
            return buildResult(ctx, "custom");
        }
    }

}