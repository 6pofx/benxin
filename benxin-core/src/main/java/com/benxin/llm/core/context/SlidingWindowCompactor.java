package com.benxin.llm.core.context;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口压缩器：从最旧的消息开始丢弃，直到估计 token 落到预算内，
 * 并在被丢弃的位置插入一条"已省略 N 条历史"的提示，让模型知道自己没有看到全部对话。
 *
 * <p>这是最廉价也最可预测的压缩策略：不调用模型、不产生额外费用、行为完全确定。
 * 需要在长任务里保留语义时，换成 {@link SummarizingCompactor}。</p>
 *
 * <p><b>保留策略</b>：{@code keepRecent} 是<b>下限</b>（与接口 javadoc 一致）——
 * 先保证最近 {@code keepRecent} 条一定留下，然后只要"系统提示词 + 占位说明 + 更早的一条"
 * 仍落在预算内，就继续往前多留一条。因此窗口大小取决于预算，但绝不会少于 {@code keepRecent}。
 * 预算与 {@code DefaultContextManager} 用同一个定义（{@link ContextCompactor#inputBudget}），
 * 不会出现"决定压缩、却认为不用压"的分歧。</p>
 */
public class SlidingWindowCompactor implements ContextCompactor {

    private final TokenEstimator estimator;
    private final double threshold;
    private final int reservedOutputTokens;

    public SlidingWindowCompactor() {
        this(TokenEstimator.DEFAULT, 0.8, 4096);
    }

    public SlidingWindowCompactor(TokenEstimator estimator, double threshold, int reservedOutputTokens) {
        this.estimator = estimator == null ? TokenEstimator.DEFAULT : estimator;
        this.threshold = threshold <= 0 ? 0.8 : threshold;
        this.reservedOutputTokens = Math.max(0, reservedOutputTokens);
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent) {
        if (history == null || history.isEmpty()) {
            return history == null ? List.of() : history;
        }
        int budget = ContextCompactor.inputBudget(model, threshold, reservedOutputTokens);
        if (estimator.estimate(history) <= budget) {
            return history;
        }

        List<ChatMessage> system = new ArrayList<>();
        List<ChatMessage> conversation = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message.role() == com.benxin.llm.core.message.Role.SYSTEM) {
                system.add(message);
            } else {
                conversation.add(message);
            }
        }
        int size = conversation.size();
        if (size == 0) {
            // 只有系统提示词：没有可丢的东西，如实原样返回
            return history;
        }

        int keep = Math.max(1, keepRecent);
        // 下界：最近 keep 条一定留下（预算再紧也不动它）
        int start = Math.max(0, size - keep);
        // 上探：把"系统提示词 + 占位说明 + 更早的一条"一起算进去，
        // 只要还落在预算内就多留一条。整份下发序列（而不只是尾部）都要计入，
        // 否则系统提示词很长时仍会超预算，下一步又得再压一次。
        int fixed = estimator.estimate(system);
        while (start > 0) {
            int candidate = start - 1;
            int cost = fixed
                    + estimator.estimate(placeholder(candidate))
                    + estimator.estimate(conversation.subList(candidate, size));
            if (cost > budget) {
                break;
            }
            start = candidate;
        }

        List<ChatMessage> result = new ArrayList<>(system);
        if (start > 0) {
            result.add(ChatMessage.user(placeholder(start)));
        }
        result.addAll(conversation.subList(start, size));
        return result;
    }

    /** 被省略 N 条时插入的占位说明（估算与最终插入用的是同一段文本）。 */
    private static String placeholder(int dropped) {
        return "[本心上下文压缩] 为控制上下文长度，已省略最早的 " + dropped
                + " 条历史消息。如需了解被省略的内容，请重新询问或使用工具自行查证。";
    }
}