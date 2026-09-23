package com.benxin.llm.core.context;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;

import java.util.List;

/**
 * 上下文压缩器：当历史逼近模型窗口时，把旧消息折叠成摘要。
 *
 * <p>claude-code 与 codex 两个 Loop 的"自动压缩"能力都落到这个接口上，
 * 因此替换压缩策略（改成向量检索回溯、改成丢弃工具结果等）不需要动 Loop。</p>
 */
public interface ContextCompactor {

    /**
     * 压缩历史。
     *
     * @param history    完整历史（含 system）
     * @param model      用于生成摘要的模型
     * @param keepRecent 至少保留的最近消息条数
     * @return 压缩后的历史
     */
    List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent);

    /** 不压缩。 */
    static ContextCompactor none() {
        return (history, model, keepRecent) -> history;
    }
}