package com.benxin.llm.spring;

/**
 * 按名字取 Agent 时找不到对应条目。
 *
 * <p>单独成一个类型是为了让 Web 层能把它映射成 <b>404</b>，而不是和其它
 * {@code IllegalArgumentException}（例如请求体不合法）一起退化成一个没有信息量的 500。
 * 它继承 {@code IllegalArgumentException}，因此任何按老口径捕获
 * {@code IllegalArgumentException} 的调用方代码不受影响。</p>
 */
public class NoSuchAgentException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String agentName;

    public NoSuchAgentException(String agentName, String message) {
        super(message);
        this.agentName = agentName;
    }

    /** 找不到的 Agent 名。 */
    public String agentName() {
        return agentName;
    }
}
