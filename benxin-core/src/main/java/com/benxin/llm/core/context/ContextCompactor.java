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
     * @param keepRecent <b>至少</b>保留的最近消息条数（下限，不是上限）：
     *                   实现可以在预算还宽裕时多留几条，但不得少于这个数
     * @return 压缩后的历史
     */
    List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent);

    /**
     * 「输入预算」的唯一定义：模型窗口减去为输出预留的部分，再乘阈值。
     *
     * <p>这个数以前在各处各写一份（{@code DefaultContextManager} 用
     * {@code (max - reserved) * threshold}，两个压缩器用 {@code max(1024, max * threshold - reserved)}），
     * 于是会出现"管理器决定压缩、压缩器却认为不用压"（或反之）的边界分歧。
     * 现在统一走这里，判断与压缩目标用同一个数。</p>
     *
     * @param model                模型；为 null 时按 128k 窗口算
     * @param threshold            触发压缩的窗口占比，非正值按 0.8
     * @param reservedOutputTokens 为输出预留的 token，负值按 0
     */
    static int inputBudget(LlmModel model, double threshold, int reservedOutputTokens) {
        int max = model == null ? 128_000 : model.capabilities().maxContextTokens();
        double ratio = threshold <= 0 ? 0.8 : threshold;
        double usable = Math.max(0, max - Math.max(0, reservedOutputTokens));
        return (int) Math.max(0, usable * ratio);
    }

    /** 不压缩。 */
    static ContextCompactor none() {
        return (history, model, keepRecent) -> history;
    }
}