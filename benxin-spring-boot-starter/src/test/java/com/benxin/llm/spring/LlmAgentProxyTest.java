package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.spring.fixture.FixtureTools;
import com.benxin.llm.spring.fixture.StubModel;
import com.benxin.llm.spring.fixture.TestAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明式 Agent 的端到端验证：注解接口 → 动态代理 → Agent 运行时 → 假模型。
 *
 * <p>这组测试回答的是整个插件最核心的一个问题：<b>写一个接口、打一个注解，真的能用吗？</b>
 * 覆盖参数语义（{@code @User} / {@code @Ctx} / {@code @Memory} / 流式回调）、
 * 返回类型的自适应、系统提示词渲染，以及工具装配。</p>
 */
@SpringBootTest(
        classes = LlmAgentProxyTest.TestApplication.class,
        properties = {
                "llm.default-model=stub",
                "llm.agent.default-loop=dsh-minimal",
                "llm.agent.logging-listener=false"
        })
class LlmAgentProxyTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableLlmAgents(basePackages = "com.benxin.llm.spring.fixture")
    @Import(FixtureTools.class)
    static class TestApplication {

        @Bean
        LlmModel stubModel() {
            return new StubModel();
        }
    }

    @Autowired
    private TestAgents.Greeter greeter;

    @Autowired
    private TestAgents.PlainAgent plainAgent;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private FixtureTools fixtureTools;

    @Autowired
    private StubModel stubModel;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("接口被注册为容器 bean，且能被直接注入")
    void registersAgentInterfacesAsBeans() {
        assertThat(greeter).isNotNull();
        assertThat(plainAgent).isNotNull();
        assertThat(agentRegistry.names()).contains("greeter", "plainAgent");
    }

    @Test
    @DisplayName("接口方法调用被翻译成一次 Agent 运行，返回 String")
    void invokesAgentThroughInterface() {
        int before = stubModel.calls();

        String answer = greeter.hello("世界");

        assertThat(answer).isEqualTo("回答：世界");
        // 假模型 bean 在整个测试上下文里共享，因此断言增量而不是绝对值
        assertThat(stubModel.calls() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("无注解参数默认作为 user 消息")
    void unannotatedParameterBecomesUserMessage() {
        greeter.hello("裸参数");

        assertThat(stubModel.lastRequest().messages())
                .anyMatch(m -> m.role() == com.benxin.llm.core.message.Role.USER
                        && m.text().equals("裸参数"));
    }

    @Test
    @DisplayName("方法级 @SystemPrompt 渲染 @Ctx 与普通参数占位符")
    void rendersSystemPromptTemplate() {
        greeter.localized("你好", "英文");

        String systemPrompt = stubModel.lastSystemPrompt();
        assertThat(systemPrompt).contains("请用 英文 回答：你好");
        // 接口级提示词默认会被继承并追加
        assertThat(systemPrompt).contains("你是一个测试助手");
    }

    @Test
    @DisplayName("@Ctx 变量只参与提示词渲染，不进入对话消息体")
    void ctxVariableDoesNotLeakIntoMessages() {
        greeter.localized("你好", "英文");

        // 系统提示词里出现 {lang} 的渲染结果是预期行为；
        // 这里要确认的是它没有变成一条 user 消息。
        assertThat(stubModel.lastRequest().messages())
                .filteredOn(m -> m.role() != com.benxin.llm.core.message.Role.SYSTEM)
                .noneMatch(m -> m.text().contains("英文"));
    }

    @Test
    @DisplayName("返回 AgentResult 时能拿到步数、用量与工具记录")
    void returnsRichResult() {
        AgentResult result = greeter.detailed("给我细节");

        assertThat(result.agentName()).isEqualTo("greeter");
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
        assertThat(result.text()).isEqualTo("回答：给我细节");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.usage().totalTokens()).isPositive();
    }

    @Test
    @DisplayName("返回 DTO 时模型输出的 JSON（含代码围栏）被自动反序列化")
    void adaptsJsonToDto() {
        TestAgents.Greeter.Greeting greeting = greeter.greeting("请用 JSON 回答");

        assertThat(greeting).isNotNull();
        assertThat(greeting.message()).isEqualTo("结构化回答");
        assertThat(greeting.length()).isEqualTo(6);
    }

    @Test
    @DisplayName("LlmStreamHandler 参数让增量实时到达，无需另一套 API")
    void streamsThroughParameter() {
        List<String> deltas = new ArrayList<>();
        String answer = greeter.streamed("流式测试", LlmStreamHandler.ofText(deltas::add));

        assertThat(answer).isEqualTo("回答：流式测试");
        assertThat(String.join("", deltas)).isEqualTo(answer);
    }

    @Test
    @DisplayName("@Memory 参数让同一 sessionId 的多轮对话续接历史")
    void remembersAcrossTurns() {
        greeter.remembered("第一句话", "session-x");
        greeter.remembered("第二句话", "session-x");

        assertThat(stubModel.lastRequest().messages())
                .anyMatch(m -> m.text().contains("第一句话"));
    }

    @Test
    @DisplayName("不同 sessionId 之间互不影响")
    void differentSessionsAreIsolated() {
        greeter.remembered("只属于 A 的内容", "session-a");
        greeter.remembered("B 的问题", "session-b");

        assertThat(stubModel.lastRequest().messages())
                .noneMatch(m -> m.text().contains("只属于 A 的内容"));
    }

    @Test
    @DisplayName("tools 指定的工具集被装配，模型调用后结果回灌")
    void assemblesDeclaredTools() {
        int before = fixtureTools.upperCalls();

        String answer = greeter.hello("请把 abc 转成大写");

        assertThat(fixtureTools.upperCalls() - before).isEqualTo(1);
        assertThat(answer).contains("工具返回");
        assertThat(answer).contains("ABC");
    }

    @Test
    @DisplayName("@LlmAgent 上的 loop / model / maxSteps 如实生效")
    void annotationAttributesTakeEffect() {
        Agent agent = agentRegistry.get("greeter");

        assertThat(agent.loop().name()).isEqualTo("dsh-minimal");
        assertThat(agent.model().name()).isEqualTo("stub");
        assertThat(agent.spec().maxSteps()).isEqualTo(5);
        assertThat(agent.tools().names()).contains("upper");
    }

    @Test
    @DisplayName("未显式指定 model 的 Agent 回落到全局默认模型")
    void fallsBackToDefaultModel() {
        assertThat(agentRegistry.get("plainAgent").model().name()).isEqualTo("stub");
    }

    @Test
    @DisplayName("未显式指定 tools 的 Agent 使用全局工具集")
    void fallsBackToGlobalTools() {
        assertThat(agentRegistry.get("plainAgent").tools().names()).contains("upper");
    }

    @Test
    @DisplayName("代理的 toString 不暴露内部结构，equals/hashCode 行为正常")
    void objectMethodsBehave() {
        assertThat(greeter.toString()).isEqualTo("@LlmAgent(" + TestAgents.Greeter.class.getName() + ")");
        assertThat(greeter.equals(greeter)).isTrue();
        assertThat(greeter.equals(plainAgent)).isFalse();
    }

    @Test
    @DisplayName("同一个接口代理是单例，重复注入拿到同一个实例")
    void agentInterfaceIsSingleton() {
        assertThat(agentRegistry.get("greeter")).isSameAs(agentRegistry.get("greeter"));
    }

    @Test
    @DisplayName("流式回调收到 onStart / onComplete 生命周期")
    void streamLifecycleCallbacks() {
        List<String> events = new ArrayList<>();
        greeter.streamed("生命周期", new LlmStreamHandler() {
            @Override
            public void onStart() {
                events.add("start");
            }

            @Override
            public void onComplete(com.benxin.llm.core.chat.ChatResponse response) {
                events.add("complete");
            }
        });

        assertThat(events).containsExactly("start", "complete");
    }
}
