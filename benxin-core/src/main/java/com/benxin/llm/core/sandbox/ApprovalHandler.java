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

    static ApprovalHandler autoApprove() {
        return request -> true;
    }

    static ApprovalHandler denyAll() {
        return request -> false;
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