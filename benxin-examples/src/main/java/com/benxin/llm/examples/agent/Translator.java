package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.model.LlmStreamHandler;

/**
 * 演示自定义 Loop（{@code step-by-step}）、会话记忆与流式回调。
 *
 * <p>接口本身没有任何变化：{@code @Memory} 参数给出会话 id 后，同一 sessionId 的
 * 多轮对话会自动续接历史；{@code LlmStreamHandler} 参数则是拿到增量输出的通道，
 * 不需要为了流式而改写接口的其余部分。</p>
 */
@LlmAgent(
        model = "mock",
        loop = "step-by-step",
        systemPrompt = "你是一名多语种翻译，先确认理解，再给出译文。",
        maxSteps = 8)
public interface Translator {

    /** 带会话记忆的多轮翻译。 */
    String translate(@User String text,
                     @Ctx("targetLanguage") String targetLanguage,
                     @Memory String sessionId);

    /** 带流式回调的翻译，调用方可以边收边渲染。 */
    @SystemPrompt("把下面的内容翻译成 {targetLanguage}，只输出译文。")
    String translateStreaming(@User String text,
                              @Ctx("targetLanguage") String targetLanguage,
                              LlmStreamHandler handler);
}