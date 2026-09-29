package com.benxin.llm.core.workflow;

/**
 * 工作流执行失败。
 *
 * <p>刻意带上节点 id 与工作流名：一张图跑挂之后，最有价值的信息就是"卡在哪个节点"。
 * 只抛一句 {@code NullPointerException} 或原始工具异常，等于把定位成本转嫁给使用者。</p>
 */
public class WorkflowException extends RuntimeException {

    private final String workflow;
    private final String nodeId;

    public WorkflowException(String workflow, String nodeId, String message) {
        super(message);
        this.workflow = workflow;
        this.nodeId = nodeId;
    }

    public WorkflowException(String workflow, String nodeId, String message, Throwable cause) {
        super(message, cause);
        this.workflow = workflow;
        this.nodeId = nodeId;
    }

    public String workflow() {
        return workflow;
    }

    /** 出错的节点 id；非节点原因（如图结构问题）时为 {@code null}。 */
    public String nodeId() {
        return nodeId;
    }
}
