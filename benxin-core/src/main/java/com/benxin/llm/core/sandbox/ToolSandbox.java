package com.benxin.llm.core.sandbox;

import com.benxin.llm.core.tool.ToolInvocation;

import java.time.Duration;

/**
 * 工具沙箱：在工具真正落地执行前做一次"守门"。
 *
 * <p>默认实现同时校验文件路径围栏与命令白名单，并可对结果做截断。
 * 需要更严格的隔离（容器、seccomp、远端执行）时，实现本接口整体替换即可。</p>
 */
public interface ToolSandbox {

    /** 校验一次调用是否放行。 */
    Decision check(ToolInvocation invocation);

    /** 单次工具执行超时。 */
    default Duration timeout() {
        return Duration.ofSeconds(60);
    }

    /** 结果字符数上限，超出截断（防止一次 {@code cat} 撑爆上下文）。 */
    default int maxResultChars() {
        return 30_000;
    }

    /** 按上限截断结果文本，并在末尾显式标注被截断。 */
    default String truncate(String content) {
        if (content == null) {
            return "";
        }
        int max = maxResultChars();
        if (max <= 0 || content.length() <= max) {
            return content;
        }
        int head = (int) (max * 0.7);
        int tail = max - head;
        return content.substring(0, head)
                + "\n\n... [本心已截断 " + (content.length() - max) + " 个字符] ...\n\n"
                + content.substring(content.length() - tail);
    }

    /** 放行一切的宽松沙箱。 */
    static ToolSandbox permissive() {
        return invocation -> Decision.allow();
    }

    /**
     * 校验结论。
     *
     * @param allowed 是否放行
     * @param reason  拒绝原因（放行时可为空）
     */
    record Decision(boolean allowed, String reason) {

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }

        public boolean denied() {
            return !allowed;
        }

        /** 拒绝结果直接转成工具错误，交回模型自我纠正。 */
        public com.benxin.llm.core.tool.ToolResult toToolResult() {
            return com.benxin.llm.core.tool.ToolResult.error(
                    "沙箱拒绝执行: " + (reason == null ? "未说明原因" : reason));
        }
    }
}