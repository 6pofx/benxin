package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.examples.tool.WeatherTools;

/**
 * 最朴素的声明式 Agent：一个接口 + 一个注解，注入即用。
 *
 * <p>{@code tools = WeatherTools.class} 只装配这一个类里的工具，
 * 而不是容器里全部工具 —— 这是"最小权限"的实践方式。</p>
 */
@LlmAgent(
        model = "mock",
        loop = "dsh-minimal",
        tools = {WeatherTools.class},
        systemPrompt = "你是一个简洁的天气助手。需要外部信息时优先调用工具，得到结果后用一两句话回答。",
        maxSteps = 6)
public interface WeatherAssistant {

    /** 无注解参数默认就是 user 内容，所以这里可以不写 @User。 */
    String ask(String question);

    /** 多参数会按声明顺序拼接为同一条 user 消息。 */
    String askWithContext(@User(prefix = "背景：") String background, @User String question);
}