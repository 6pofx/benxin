package com.benxin.llm.spring.fixture;

import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.model.LlmStreamHandler;

/** 被扫描的 {@code @LlmAgent} 接口集合。 */
public final class TestAgents {

    private TestAgents() {
    }

    /**
     * 基础形态：注解 + 方法，注入即用。
     *
     * <p>只装配 {@link FixtureTools}，因此 {@code never_used} 也在工具集里 ——
     * 但对另一个只用全局工具的 Agent 来说，工具集应当是不同的。</p>
     */
    @LlmAgent(name = "greeter", model = "stub", loop = "dsh-minimal",
            tools = {FixtureTools.class},
            systemPrompt = "你是一个测试助手",
            maxSteps = 5)
    public interface Greeter {

        String hello(String name);

        @SystemPrompt("请用 {lang} 回答：{question}")
        String localized(@User String question, @Ctx("lang") String lang);

        AgentResult detailed(@User String question);

        Greeting greeting(@User String question);

        String streamed(@User String question, LlmStreamHandler handler);

        String remembered(@User String question, @Memory String sessionId);

        /** 强类型返回值的载体。 */
        record Greeting(String message, int length) {
        }
    }

    /** 不指定 tools，应当回落到全局工具集。 */
    @LlmAgent(name = "plainAgent", model = "stub", loop = "react")
    public interface PlainAgent {
        String ask(String question);
    }
}