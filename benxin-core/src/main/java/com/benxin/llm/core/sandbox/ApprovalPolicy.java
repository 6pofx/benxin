package com.benxin.llm.core.sandbox;

/**
 * 审批策略：什么情况下需要"人点头"才允许工具落地。
 *
 * <p>默认 {@link #ON_REQUEST}：只有工具自己声明"我需要审批"（例如写文件、执行命令）
 * 才会询问，读类工具直接放行。这是安全与体验的折中默认值。</p>
 */
public enum ApprovalPolicy {

    /** 从不询问，一律放行。适合完全可信的沙箱或只读工具集。 */
    NEVER,

    /** 仅在工具显式要求审批时询问（默认）。 */
    ON_REQUEST,

    /** 每次工具调用都要审批。 */
    ALWAYS,

    /**
     * 先执行；仅当执行失败时，再询问是否要放行重试（批准则重试一次）。
     *
     * <p>注意沙箱拒绝<b>不</b>在此列：那是"这条路径/这条命令不该被执行"的硬边界，
     * 不是一次运行期的失败，任何审批策略都覆盖不了它（默认的 {@code autoApprove} 尤其不能）。</p>
     */
    ON_FAILURE;

    public static ApprovalPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return ON_REQUEST;
        }
        String v = value.trim().toUpperCase().replace('-', '_');
        for (ApprovalPolicy p : values()) {
            if (p.name().equals(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知审批策略: " + value);
    }
}