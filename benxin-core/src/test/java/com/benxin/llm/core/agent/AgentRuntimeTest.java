package com.benxin.llm.core.agent;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.DshMinimalLoop;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.support.SampleTools;
import com.benxin.llm.core.support.ScriptedModel;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 运行时：把"模型 → 工具 → 沙箱 → 审批 → 拦截器 → 记忆"整条链路的边界行为钉死。
 *
 * <p>这里刻意不依赖 Spring、不依赖网络 —— 印证了 benxin-core 可以脱离框架独立使用。</p>
 */
class AgentRuntimeTest {

    /**
     * 装配一个测试用 Agent。默认：dsh-minimal 循环、无工具、无记忆、8 步上限 ——
     * 每个测试只声明自己关心的差异，避免样板淹没断言。
     */
    private static Agent agent(LlmModel model, Consumer<AgentBuilder> customizer) {
        AgentBuilder builder = Agent.builder("test-agent")
                .model(model)
                .loop(new DshMinimalLoop())
                .maxSteps(8);
        if (customizer != null) {
            customizer.accept(builder);
        }
        return builder.build();
    }

    /** 一个按参数回显的手写工具，用于观察是否真的被执行、参数是否正确绑定。 */
    private static ToolCallback counter(String name, AtomicInteger executions) {
        return new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "计数工具", Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.ok(name + ":" + arguments.get("n"));
            }
        };
    }

    /** 需要人工审批的写类工具。 */
    private static ToolCallback guardedWriter(AtomicInteger executions) {
        return new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("write", "写文件", Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.ok("已写入");
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }
        };
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具调用闭环：执行 → 结果回灌 → 模型收尾")
    void executesToolAndFeedsResultBack() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("echo", "{\"text\":\"你好\"}"),
                ScriptedModel.text("完成"));

        Agent agent = agent(model, b -> b.tool(SampleTools.echoTool("echo")));
        AgentResult result = agent.call("请回显");

        assertThat(result.text()).isEqualTo("完成");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("echo");
        assertThat(result.toolCalls().get(0).result()).isEqualTo("echo:你好");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        // 第二次请求里必须能看到工具结果，否则模型无从收尾
        assertThat(model.lastRequestContainsToolResult("echo")).isTrue();
    }

    @Test
    @DisplayName("未知工具：转成错误结果交回模型，而不是抛异常")
    void unknownToolBecomesError() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("not_exists", "{}"),
                ScriptedModel.text("好的"));

        AgentResult result = agent(model, null).call("x");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).error()).isTrue();
        assertThat(result.toolCalls().get(0).result()).contains("未知工具");
        assertThat(result.text()).isEqualTo("好的");
    }

    @Test
    @DisplayName("沙箱拒绝：工具根本不被执行，且模型收到明确原因")
    void sandboxDenialBlocksExecution() {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("danger", "{}"),
                ScriptedModel.text("明白"));

        Agent agent = agent(model, b -> b
                .tool(counter("danger", executions))
                .sandbox(invocation -> ToolSandbox.Decision.deny("目录越界")));

        AgentResult result = agent.call("x");

        assertThat(executions).hasValue(0);
        assertThat(result.toolCalls().get(0).error()).isTrue();
        assertThat(result.toolCalls().get(0).result()).contains("沙箱拒绝").contains("目录越界");
    }

    @Test
    @DisplayName("审批被拒：工具不执行，理由回灌给模型")
    void approvalDenialBlocksExecution() {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("write", "{}"),
                ScriptedModel.text("好的"));

        Agent agent = agent(model, b -> b
                .tool(guardedWriter(executions))
                .approvalPolicy(ApprovalPolicy.ON_REQUEST)
                .approvalHandler(ApprovalHandler.denyAll()));

        AgentResult result = agent.call("x");

        assertThat(executions).hasValue(0);
        assertThat(result.toolCalls().get(0).error()).isTrue();
        assertThat(result.toolCalls().get(0).result()).contains("拒绝");
    }

    @Test
    @DisplayName("NEVER 策略下即使工具要求审批也直接放行")
    void neverPolicySkipsApproval() {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("write", "{}"),
                ScriptedModel.text("好的"));

        Agent agent = agent(model, b -> b
                .tool(guardedWriter(executions))
                .approvalPolicy(ApprovalPolicy.NEVER)
                .approvalHandler(ApprovalHandler.denyAll()));

        agent.call("x");
        assertThat(executions).hasValue(1);
    }

    @Test
    @DisplayName("拦截器可以短路工具执行")
    void interceptorShortCircuitsTool() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("echo", "{\"text\":\"hi\"}"),
                ScriptedModel.text("完成"));

        Agent agent = agent(model, b -> b
                .tool(SampleTools.echoTool("echo"))
                .interceptor(new AgentInterceptor() {
                    @Override
                    public ToolResult beforeTool(ToolInvocation invocation) {
                        return ToolResult.ok("被拦截器改写的结果");
                    }
                }));

        AgentResult result = agent.call("x");
        assertThat(result.toolCalls().get(0).result()).isEqualTo("被拦截器改写的结果");
    }

    @Test
    @DisplayName("拦截器可以改写下发给模型的请求")
    void interceptorRewritesModelRequest() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("ok"));
        Agent agent = agent(model, b -> b.interceptor(new AgentInterceptor() {
            @Override
            public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
                return request.toBuilder().extra("injected", true).temperature(0.42).build();
            }
        }));

        agent.call("x");
        assertThat(model.lastRequest().temperature()).isEqualTo(0.42);
        assertThat(model.lastRequest().extra()).containsEntry("injected", true);
    }

    @Test
    @DisplayName("returnDirect 工具的结果直接作为最终答案，不再回模型")
    void returnDirectEndsLoopImmediately() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.toolCall("direct", "{}"));
        Agent agent = agent(model, b -> b.tool(SampleTools.directTool("direct")));

        AgentResult result = agent.call("x");

        assertThat(result.text()).isEqualTo("直接答案");
        // 只调用了一次模型：工具结果没有被再送回去
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("触达步数上限时标记 maxStepsReached 并返回已有内容")
    void stopsAtMaxSteps() {
        ScriptedModel model = ScriptedModel.repeating(
                ScriptedModel.toolCall("echo", "{\"text\":\"loop\"}"), 20);
        Agent agent = agent(model, b -> b
                .tool(SampleTools.echoTool("echo"))
                .maxSteps(3));

        AgentResult result = agent.call("无限循环");

        assertThat(result.maxStepsReached()).isTrue();
        assertThat(result.steps()).isEqualTo(3);
        assertThat(model.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("开启记忆后，同一 sessionId 的多轮对话自动续接")
    void memoryPersistsAcrossCalls() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("第一轮回答"),
                ScriptedModel.text("第二轮回答"));
        Agent agent = agent(model, b -> b.memory(true));

        agent.call("第一轮问题", "session-a");
        agent.call("第二轮问题", "session-a");

        ChatRequest second = model.lastRequest();
        assertThat(second.messages()).anyMatch(m -> m.text().contains("第一轮问题"));
        assertThat(second.messages()).anyMatch(m -> m.text().contains("第一轮回答"));
        assertThat(agent.history("session-a")).isNotEmpty();
    }

    @Test
    @DisplayName("关闭记忆时，历史互不干扰")
    void memoryDisabledKeepsSessionsIndependent() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.text("a"),
                ScriptedModel.text("b"));
        Agent agent = agent(model, b -> b.memory(false));

        agent.call("问题一", "s1");
        agent.call("问题二", "s1");

        assertThat(model.lastRequest().messages()).noneMatch(m -> m.text().contains("问题一"));
    }

    @Test
    @DisplayName("监听器收到完整的生命周期事件，顺序稳定")
    void listenerReceivesLifecycleEvents() {
        List<String> events = new ArrayList<>();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("echo", "{\"text\":\"x\"}"),
                ScriptedModel.text("完成"));

        Agent agent = agent(model, b -> b
                .tool(SampleTools.echoTool("echo"))
                .listener(new AgentListener() {
                    @Override public void onAgentStart(AgentInvocation invocation) { events.add("start"); }
                    @Override public void onStepStart(String agentName, int step) { events.add("step" + step); }
                    @Override public void onToolCall(ToolInvocation invocation) { events.add("tool"); }
                    @Override public void onToolResult(ToolInvocation invocation, ToolResult result) { events.add("result"); }
                    @Override public void onAgentEnd(AgentResult result) { events.add("end"); }
                }));

        agent.call("x");

        assertThat(events).containsExactly("start", "step1", "tool", "result", "step2", "end");
    }

    @Test
    @DisplayName("并行工具调用的结果顺序与请求顺序一致")
    void parallelToolCallsPreserveOrder() {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCalls(
                        new ToolUsePart("c1", "slow", "{\"n\":1}"),
                        new ToolUsePart("c2", "slow", "{\"n\":2}"),
                        new ToolUsePart("c3", "slow", "{\"n\":3}")),
                ScriptedModel.text("完成"));

        Agent agent = agent(model, b -> b.tool(counter("slow", executions)));
        AgentResult result = agent.call("并发");

        assertThat(executions).hasValue(3);
        assertThat(result.toolCalls()).extracting(r -> r.result())
                .containsExactly("slow:1", "slow:2", "slow:3");
    }

    @Test
    @DisplayName("工具超时被转成错误结果，不会把整个 Agent 挂死")
    void toolTimeoutBecomesError() {
        ToolCallback hanging = new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("hang", "卡住的工具", Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok("不该到这里");
            }
        };

        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("hang", "{}"),
                ScriptedModel.text("好的"));

        Agent agent = agent(model, b -> b
                .tool(hanging)
                .sandbox(new ToolSandbox() {
                    @Override
                    public Decision check(ToolInvocation invocation) {
                        return Decision.allow();
                    }

                    @Override
                    public Duration timeout() {
                        return Duration.ofMillis(120);
                    }
                }));

        AgentResult result = agent.call("x");
        assertThat(result.toolCalls().get(0).error()).isTrue();
        assertThat(result.toolCalls().get(0).result()).contains("超时");
    }

    @Test
    @DisplayName("沙箱可以对结果做截断，防止一次输出撑爆上下文")
    void sandboxTruncatesLargeResult() {
        ToolCallback verbose = new ToolCallback() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("verbose", "话很多的工具", Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                return ToolResult.ok("x".repeat(5000));
            }
        };

        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("verbose", "{}"),
                ScriptedModel.text("好的"));

        Agent agent = agent(model, b -> b
                .tool(verbose)
                .sandbox(new ToolSandbox() {
                    @Override
                    public Decision check(ToolInvocation invocation) {
                        return Decision.allow();
                    }

                    @Override
                    public int maxResultChars() {
                        return 200;
                    }
                }));

        AgentResult result = agent.call("x");
        assertThat(result.toolCalls().get(0).result()).contains("已截断");
        assertThat(result.toolCalls().get(0).result().length()).isLessThan(400);
    }

    @Test
    @DisplayName("用量在多次模型调用间累加")
    void accumulatesUsageAcrossSteps() {
        ScriptedModel model = ScriptedModel.of(
                ScriptedModel.toolCall("echo", "{\"text\":\"x\"}"),
                ScriptedModel.text("完成"));

        Agent agent = agent(model, b -> b.tool(SampleTools.echoTool("echo")));
        AgentResult result = agent.call("x");

        // ScriptedModel 每次返回 Usage(10, 5)
        assertThat(result.usage().inputTokens()).isEqualTo(20);
        assertThat(result.usage().outputTokens()).isEqualTo(10);
        assertThat(result.usage().totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("纯文本调用不产生工具记录，步数为 1")
    void plainTextCallHasSingleStep() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("直接答案"));
        AgentResult result = agent(model, null).call("你好");

        assertThat(result.text()).isEqualTo("直接答案");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("toBuilder 派生的 Agent 与原实例互不影响 —— 一切可替换的最小验证")
    void derivedAgentIsIndependent() {
        ScriptedModel model = ScriptedModel.of(ScriptedModel.text("ok"));
        Agent base = agent(model, b -> b.memory(true).maxSteps(4));

        Agent derived = base.toBuilder()
                .maxSteps(99)
                .memory(false)
                .interceptor(new AgentInterceptor() {
                    @Override
                    public int order() {
                        return 1;
                    }
                })
                .build();

        assertThat(base.spec().maxSteps()).isEqualTo(4);
        assertThat(derived.spec().maxSteps()).isEqualTo(99);
        assertThat(base.spec().memory()).isTrue();
        assertThat(derived.spec().memory()).isFalse();
        assertThat(derived.name()).isEqualTo(base.name());
    }
}
