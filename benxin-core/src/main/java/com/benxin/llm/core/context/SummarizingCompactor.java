package com.benxin.llm.core.context;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要式压缩器：把较早的历史交给模型压成一段结构化摘要，只保留最近的若干条原文。
 *
 * <p>这是 claude-code / codex 那类"长任务自动压缩"能力的通用实现。
 * 关键取舍：</p>
 * <ul>
 *   <li>摘要**以 user 消息**插入而非 assistant —— 让模型把摘要当成"已知背景"而不是
 *       "自己说过的话"，避免它在后续回复里误以为摘要就是自己的输出。</li>
 *   <li>摘要模板**显式要求保留**文件路径、函数名、待办、未解决的错误、用户偏好，
 *       这些恰好是压缩后最容易丢、丢了代价最大的信息。</li>
 *   <li>任何失败（模型报错、摘要为空）都**退化为滑动窗口**，绝不因为压缩失败而让
 *       整个 Agent 挂掉。</li>
 * </ul>
 */
public class SummarizingCompactor implements ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(SummarizingCompactor.class);

    private static final String DEFAULT_INSTRUCTION = """
            请把下面这段对话历史压缩成一份高密度的工作摘要，供后续继续任务时使用。
            必须保留（如果存在）：
            1. 用户的核心诉求与已确认的约束、偏好
            2. 涉及的文件路径、类名、方法名、命令
            3. 已完成的改动与结论
            4. 尚未解决的问题、报错信息的关键部分
            5. 当前进度与下一步计划
            不要保留：寒暄、重复的解释、已被推翻的中间尝试。
            直接输出摘要正文，不要任何前言或"以下是摘要"之类的套话。
            """;

    private final TokenEstimator estimator;
    private final double threshold;
    private final SlidingWindowCompactor fallback;
    private final String instruction;

    public SummarizingCompactor() {
        this(TokenEstimator.DEFAULT, 0.8, DEFAULT_INSTRUCTION);
    }

    public SummarizingCompactor(TokenEstimator estimator, double threshold, String instruction) {
        this.estimator = estimator == null ? TokenEstimator.DEFAULT : estimator;
        this.threshold = threshold <= 0 ? 0.8 : threshold;
        this.fallback = new SlidingWindowCompactor(this.estimator, this.threshold, 4096);
        this.instruction = instruction == null || instruction.isBlank() ? DEFAULT_INSTRUCTION : instruction;
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent) {
        if (history == null || history.isEmpty() || model == null) {
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
        int split = Math.max(0, conversation.size() - keep);
        if (split == 0) {
            // 最近的消息本身就超预算，摘要无从下手，交给滑动窗口
            return fallback.compact(history, model, keepRecent);
        }

        List<ChatMessage> older = conversation.subList(0, split);
        List<ChatMessage> recent = conversation.subList(split, conversation.size());

        String digest = summarize(older, model);
        if (digest == null || digest.isBlank()) {
            log.debug("摘要为空，退化为滑动窗口压缩");
            return fallback.compact(history, model, keepRecent);
        }

        List<ChatMessage> result = new ArrayList<>(system);
        result.add(ChatMessage.user("[本心历史摘要] 以下是更早对话的压缩摘要，请把它当作已知背景：\n\n" + digest));
        result.addAll(recent);

        // 摘要本身也可能仍然超预算，再兜一层
        if (estimator.estimate(result) > budget) {
            return fallback.compact(result, model, keepRecent);
        }
        return result;
    }

    private String summarize(List<ChatMessage> older, LlmModel model) {
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage message : older) {
            String text = message.text();
            if (text.isBlank()) {
                continue;
            }
            transcript.append(message.role().wireName()).append(": ").append(text).append('\n');
        }
        if (transcript.isEmpty()) {
            return null;
        }
        // 单次摘要请求也要有自己的上限，否则"为了压缩反而超窗"
        int maxChars = 60_000;
        String body = transcript.length() > maxChars
                ? transcript.substring(transcript.length() - maxChars)
                : transcript.toString();

        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .message(ChatMessage.system(instruction))
                    .message(ChatMessage.user(body))
                    .temperature(0.2)
                    .build());
            return response.text();
        } catch (RuntimeException e) {
            log.warn("生成历史摘要失败，退化为滑动窗口压缩: {}", e.toString());
            return null;
        }
    }

    private int budgetOf(LlmModel model) {
        return (int) Math.max(1024, model.capabilities().maxContextTokens() * threshold - 4096);
    }
}