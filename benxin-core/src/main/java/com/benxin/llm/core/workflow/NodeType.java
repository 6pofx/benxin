package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工作流节点类型。
 *
 * <p>能表达"顺序、分支、循环、汇合"这四件事就已经是图灵完备的了，因此类型刻意保持很少 ——
 * 每多一种，YAML 就更接近一门需要另写文档的编程语言。总体主张没变：
 * <b>编排归图，能力归工具</b>。</p>
 *
 * <p>{@link #JAVA} 是唯一的例外，而且它是"为了不再增加类型"才加的：它是一道<b>逃生口</b>。
 * 当一段逻辑既不适合写成提示词、又不该交给模型当工具用时（业务规则、格式转换、校验、
 * 与本地系统打交道），就写成一个注册好的处理器挂在 Java 节点上，
 * 而不是继续往这张枚举里加第七种、第八种节点。它有三个刻意的边界：
 * 不能调模型、不能调工具、不能直接指定下一条边 —— 详见 {@link WorkflowHandler}。</p>
 *
 * <p>循环不需要专门的节点类型：把边指回上游节点即可，由
 * {@link WorkflowEngine} 的步数预算与单节点访问上限兜底。</p>
 */
public enum NodeType {

    /** 入口：渲染 {@code prompt}（缺省时直接透传工作流输入）作为起点文本。 */
    START("start"),

    /** 一次完整的模型往返（可带工具），由 {@code prompt} 驱动。 */
    AGENT("agent"),

    /** 直接调用一个已注册的工具，参数来自 {@code args} 模板。 */
    TOOL("tool"),

    /**
     * 执行一段宿主注册的本地逻辑：{@code handler} 指向 {@link WorkflowHandlerRegistry} 里的名字。
     *
     * <p>面向图而不是模型：不进工具 Schema、不经沙箱与审批，结果可以带回变量。</p>
     */
    JAVA("java"),

    /** 纯路由节点：自己不产生输出，只按出边的条件挑一条走。 */
    BRANCH("branch"),

    /** 写变量：把 {@code set} 里的模板求值后写入黑板，供后续节点引用。 */
    SET("set"),

    /** 终点：渲染 {@code output} 作为工作流的最终答案。 */
    END("end");

    private final String wireName;

    NodeType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 宽容解析：接受小写名，也接受几个一眼能懂的别名。
     *
     * <p>写 YAML 的人不该为了 {@code llm} 还是 {@code agent} 去翻文档，
     * 因此常见的同义写法一律接受，实在不认识才报错并列出可选值。</p>
     */
    @JsonCreator
    public static NodeType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return AGENT;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (NodeType type : values()) {
            if (type.wireName.equals(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return switch (value) {
            case "llm", "model", "prompt", "call" -> AGENT;
            case "call-tool", "invoke", "function" -> TOOL;
            case "handler", "code", "custom", "java-node" -> JAVA;
            case "condition", "switch", "if", "route" -> BRANCH;
            case "assign", "var", "variable" -> SET;
            case "finish", "exit", "result" -> END;
            case "entry", "begin" -> START;
            default -> throw new IllegalArgumentException(
                    "未知节点类型 [" + raw + "]，可选: start / agent / tool / java / branch / set / end");
        };
    }
}
