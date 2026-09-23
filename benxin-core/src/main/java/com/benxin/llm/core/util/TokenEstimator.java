package com.benxin.llm.core.util;

import com.benxin.llm.core.message.ChatMessage;

import java.util.List;

/**
 * 轻量 token 估算器。默认实现按"中日韩字符 1 token / 1 字，其余约 4 字符 1 token"估算，
 * 不依赖任何分词库，足以驱动上下文压缩的阈值判断。
 *
 * <p>接入真实 tokenizer 只需实现本接口并注册为 bean 覆盖默认实现。</p>
 */
public interface TokenEstimator {

    TokenEstimator DEFAULT = new TokenEstimator() {
    };

    /** 估算一段文本的 token 数。 */
    default int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7AF || c >= 0xF900 && c <= 0xFAFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + Math.max(1, other / 4);
    }

    /** 估算一组消息的 token 数（含每条消息约 4 token 的角色/分隔开销）。 */
    default int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += 4 + estimate(message.text());
            total += message.parts().size() * 8;
        }
        return total;
    }
}