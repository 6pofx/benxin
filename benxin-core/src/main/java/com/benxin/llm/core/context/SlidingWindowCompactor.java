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
        int budget = budgetOf(model);
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

        int keep = Math.max(1, keepRecent);
        // 优先按预算从尾部保留，同时不少于 keepRecent 条
        int start = Math.max(0, conversation.size() - keep);
        for (int candidate = conversation.size() - 1; candidate >= start; candidate--) {
            List<ChatMessage> tail = conversation.subList(candidate, conversation.size());
            if (estimator.estimate(tail) > budget && candidate < conversation.size() - 1) {
                start = candidate + 1;
                break;
            }
            start = candidate;
        }

        int dropped = start;
        List<ChatMessage> result = new ArrayList<>(system);
        if (dropped > 0) {
            result.add(ChatMessage.user("[本心上下文压缩] 为控制上下文长度，已省略最早的 "
                    + dropped + " 条历史消息。如需了解被省略的内容，请重新询问或使用工具自行查证。"));
        }
        result.addAll(conversation.subList(start, conversation.size()));
        return result;
    }

    private int budgetOf(LlmModel model) {
        int max = model == null ? 128_000 : model.capabilities().maxContextTokens();
        return (int) Math.max(1024, max * threshold - reservedOutputTokens);
    }
}