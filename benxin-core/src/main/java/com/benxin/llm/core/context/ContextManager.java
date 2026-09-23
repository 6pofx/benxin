package com.benxin.llm.core.context;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;

import java.util.List;

/**
 * 上下文管理器：每次调用模型之前，决定"真正发给模型的消息序列长什么样"。
 *
 * <p>这是与模型窗口、成本控制、长任务续跑相关的一切逻辑的收口点。</p>
 */
public interface ContextManager {

    /**
     * 组装本次要下发的消息。
     *
     * @param systemPrompt 系统提示词（可能为空）
     * @param history      会话历史
     * @param model        目标模型
     */
    List<ChatMessage> prepare(String systemPrompt, List<ChatMessage> history, LlmModel model);

    /** 一步结束后通知，用于统计用量、判断是否需要触发压缩。 */
    default void afterStep(List<ChatMessage> history, LlmModel model) {
    }
}