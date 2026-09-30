package com.benxin.llm.core.workflow;

import java.util.List;

/**
 * 一次工作流运行的产物。
 *
 * @param workflow   工作流名
 * @param output     最终输出（END 节点渲染的结果，或最后一个产生输出的节点的输出）
 * @param visited    实际走过的节点序列，含重复（循环会重复出现）—— 这是排障时最有用的一份记录。
 *                   一次"尝试执行"就算一次访问：护栏拦下的一次、硬失败的一次都会出现在这里，
 *                   因此轨迹证明的是"尝试过哪些节点"，而不只是"成功走过哪些节点"
 * @param executions 节点访问次数（<b>不含重试</b>；一次访问 = 一条 {@code workflow.node} 事件）。
 *                   重试记在 {@code workflow.node-retry} 事件里，不进这个计数
 * @param truncated  是否因步数预算耗尽而中断（为 true 时 {@code output} 只是半成品）
 */
public record WorkflowRun(String workflow, String output, List<String> visited, int executions,
                          boolean truncated) {

    public WorkflowRun {
        visited = List.copyOf(visited);
        output = output == null ? "" : output;
    }

    /** 是否正常走完（未因预算中断）。 */
    public boolean completed() {
        return !truncated;
    }

    /** 走过的节点序列去重后的形式，便于展示"路径"。 */
    public List<String> distinctVisited() {
        return visited.stream().distinct().toList();
    }
}
