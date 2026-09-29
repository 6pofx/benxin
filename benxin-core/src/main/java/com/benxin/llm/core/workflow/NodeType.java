package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工作流节点类型。
 *
 * <p>刻意只保留六种：能表达"顺序、分支、循环、汇合"这四件事就已经是本意图灵完备的了，
 * 再多的类型只会让 YAML 变成一门需要另写文档的编程语言。复杂逻辑应当下沉到工具里，
 * 而不是在这层堆节点种类 —— 这是本心一贯的取舍：<b>编排归图，能力归工具</b>。</p>
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
            case "condition", "switch", "if", "route" -> BRANCH;
            case "assign", "var", "variable" -> SET;
            case "finish", "exit", "result" -> END;
            case "entry", "begin" -> START;
            default -> throw new IllegalArgumentException(
                    "未知节点类型 [" + raw + "]，可选: start / agent / tool / branch / set / end");
        };
    }
}
