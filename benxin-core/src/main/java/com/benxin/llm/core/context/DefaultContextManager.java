package com.benxin.llm.core.context;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 默认上下文管理器：
 * <ol>
 *   <li>把系统提示词放到最前面（已存在则不重复添加）；</li>
 *   <li>估算 token，超过模型窗口的 {@code threshold} 比例时调用压缩器；</li>
 *   <li>返回可直接下发的消息序列。</li>
 * </ol>
 */
public class DefaultContextManager implements ContextManager {

    private final ContextCompactor compactor;
    private final TokenEstimator estimator;
    private final double threshold;
    private final int keepRecent;
    private final int reservedOutputTokens;

    public DefaultContextManager() {
        this(ContextCompactor.none(), TokenEstimator.DEFAULT, 0.8, 8, 4096);
    }

    public DefaultContextManager(ContextCompactor compactor, TokenEstimator estimator,
                                 double threshold, int keepRecent, int reservedOutputTokens) {
        this.compactor = compactor == null ? ContextCompactor.none() : compactor;
        this.estimator = estimator == null ? TokenEstimator.DEFAULT : estimator;
        this.threshold = threshold <= 0 ? 0.8 : threshold;
        this.keepRecent = Math.max(1, keepRecent);
        this.reservedOutputTokens = Math.max(0, reservedOutputTokens);
    }

    @Override
    public List<ChatMessage> prepare(String systemPrompt, List<ChatMessage> history, LlmModel model) {
        List<ChatMessage> working = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            working.add(ChatMessage.system(systemPrompt));
        }
        if (history != null) {
            for (ChatMessage message : history) {
                if (message.role() == com.benxin.llm.core.message.Role.SYSTEM) {
                    continue; // 系统提示词由参数统一提供，避免重复
                }
                working.add(message);
            }
        }
        int budget = budgetOf(model);
        if (budget > 0 && estimator.estimate(working) > budget) {
            List<ChatMessage> compacted = compactor.compact(working, model, keepRecent);
            if (compacted != null && !compacted.isEmpty()) {
                return compacted;
            }
        }
        return working;
    }

    /**
     * 输入预算：与压缩器用同一个定义（{@link ContextCompactor#inputBudget}）。
     *
     * <p>以前这里是 {@code (max - reserved) * threshold}、压缩器里是
     * {@code max(1024, max * threshold - reserved)} —— 两个数不同，
     * 边界上会出现"管理器决定压缩、压缩器却认为不用压"。</p>
     */
    private int budgetOf(LlmModel model) {
        return ContextCompactor.inputBudget(model, threshold, reservedOutputTokens);
    }
}