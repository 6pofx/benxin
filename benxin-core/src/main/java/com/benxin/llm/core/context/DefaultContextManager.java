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
        int budget = budgetOf(model) - reservedOutputTokens;
        if (budget > 0 && estimator.estimate(working) > budget * threshold) {
            List<ChatMessage> compacted = compactor.compact(working, model, keepRecent);
            if (compacted != null && !compacted.isEmpty()) {
                return compacted;
            }
        }
        return working;
    }

    private int budgetOf(LlmModel model) {
        return Optional.ofNullable(model)
                .map(m -> m.capabilities().maxContextTokens())
                .orElse(128_000);
    }
}