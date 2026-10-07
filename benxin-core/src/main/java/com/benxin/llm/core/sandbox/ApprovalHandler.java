package com.benxin.llm.core.sandbox;

import java.util.Map;

/**
 * 审批处理器：把"要不要放行"这个决定交给人或外部系统。
 *
 * <p>Web 场景可实现为向前端推送确认弹窗并等待；CLI 场景可读标准输入；
 * 无人值守场景用 {@link #autoApprove()} 或 {@link #denyAll()}。</p>
 */
public interface ApprovalHandler {

    boolean approve(ApprovalRequest request);

    /**
     * 判定一次审批，返回带理由的结论。
     *
     * <p>默认实现把 {@link #approve} 的布尔值包成结论（拒绝时没有理由），
     * 因此只实现 {@code approve} 的既有代码行为完全不变。想在拒绝时告诉模型
     * "为什么不行"（例如"写文件需先说明目标路径"）的实现覆写本方法即可。</p>
     */
    default ApprovalDecision decide(ApprovalRequest request) {
        return approve(request) ? ApprovalDecision.allow() : ApprovalDecision.denied(null);
    }

    static ApprovalHandler autoApprove() {
        return request -> true;
    }

    static ApprovalHandler denyAll() {
        return request -> false;
    }

    /**
     * 审批结论。
     *
     * <p>注意静态工厂叫 {@link #allow()} 而不是 {@code approved()}：记录组件 {@code approved}
     * 已经占用了同签名的访问器方法名。</p>
     *
     * @param approved 是否放行
     * @param reason   拒绝理由，会原样拼进回传给模型的工具错误文本；无理由时为 {@code null}
     */
    record ApprovalDecision(boolean approved, String reason) {

        public static ApprovalDecision allow() {
            return new ApprovalDecision(true, null);
        }

        public static ApprovalDecision denied(String reason) {
            return new ApprovalDecision(false, reason);
        }
    }

    /**
     * 审批请求。
     *
     * @param toolName  工具名
     * @param arguments 调用参数
     * @param reason    为什么需要审批
     * @param agentName 发起方
     * @param sessionId 会话
     */
    record ApprovalRequest(String toolName, Map<String, Object> arguments, String reason,
                           String agentName, String sessionId) {

        public ApprovalRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        public String summary() {
            return "[" + agentName + "] 请求执行工具 " + toolName
                    + " " + com.benxin.llm.core.util.Json.writeQuietly(arguments)
                    + (reason == null ? "" : " —— " + reason);
        }
    }
}