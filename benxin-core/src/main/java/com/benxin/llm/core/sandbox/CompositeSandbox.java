package com.benxin.llm.core.sandbox;

import com.benxin.llm.core.tool.ToolInvocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 组合沙箱：把多个 {@link ToolSandbox} 串成一条链。
 *
 * <p>语义刻意做成"与"而不是"或"：<b>任何一个拒绝即拒绝</b>（fail-fast，第一条拒绝原因
 * 直接成为结论）。安全组件只能越叠越严，一旦做成"或"，多装一层反而会削弱防护。</p>
 *
 * <p>{@link #timeout()} 与 {@link #maxResultChars()} 取所有成员里<b>最严格</b>（最小）的值：
 * 组合体不应该比它的任何单个成员更宽松。</p>
 *
 * <p>典型用法就是本心内置工具集的默认装配：</p>
 * <pre>{@code
 * ToolSandbox sandbox = CompositeSandbox.of(new PathSandbox(config), new CommandSandbox(config));
 * }</pre>
 */
public final class CompositeSandbox implements ToolSandbox {

    private final List<ToolSandbox> members;

    private CompositeSandbox(List<ToolSandbox> members) {
        this.members = List.copyOf(members);
    }

    /** 组装任意多个沙箱；{@code null} 成员会被忽略。 */
    public static CompositeSandbox of(ToolSandbox... sandboxes) {
        List<ToolSandbox> members = new ArrayList<>();
        if (sandboxes != null) {
            for (ToolSandbox sandbox : sandboxes) {
                if (sandbox != null) {
                    members.add(sandbox);
                }
            }
        }
        return new CompositeSandbox(members);
    }

    /** 组装任意多个沙箱（列表形式）。 */
    public static CompositeSandbox of(List<ToolSandbox> sandboxes) {
        return of(sandboxes == null ? new ToolSandbox[0] : sandboxes.toArray(new ToolSandbox[0]));
    }

    /** 成员快照，便于调试与自检。 */
    public List<ToolSandbox> members() {
        return members;
    }

    @Override
    public Decision check(ToolInvocation invocation) {
        for (ToolSandbox member : members) {
            Decision decision = member.check(invocation);
            if (decision == null || decision.denied()) {
                // fail-fast：第一条拒绝原因即结论，不再往下问。
                return decision == null
                        ? Decision.deny("沙箱 " + member.getClass().getSimpleName() + " 返回了空结论，按拒绝处理。")
                        : decision;
            }
        }
        return Decision.allow();
    }

    /** 取最严格的超时；没有成员时退回接口默认值。 */
    @Override
    public Duration timeout() {
        Duration strictest = null;
        for (ToolSandbox member : members) {
            Duration candidate = member.timeout();
            if (candidate == null) {
                continue;
            }
            if (strictest == null || candidate.compareTo(strictest) < 0) {
                strictest = candidate;
            }
        }
        return strictest == null ? ToolSandbox.super.timeout() : strictest;
    }

    /** 取最严格的结果上限；没有成员时退回接口默认值。 */
    @Override
    public int maxResultChars() {
        int strictest = Integer.MAX_VALUE;
        for (ToolSandbox member : members) {
            int candidate = member.maxResultChars();
            if (candidate > 0 && candidate < strictest) {
                strictest = candidate;
            }
        }
        return strictest == Integer.MAX_VALUE ? ToolSandbox.super.maxResultChars() : strictest;
    }

    @Override
    public String toString() {
        return "CompositeSandbox" + Arrays.toString(members.stream()
                .map(member -> member.getClass().getSimpleName())
                .toArray());
    }
}
