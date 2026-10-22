package com.benxin.llm.core.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 一个 agent 节点<b>能看到多少上文</b>。
 *
 * <p>这是工作流模式里最容易被忽略、又最影响行为的一个开关。</p>
 *
 * <table border="1">
 *   <caption>两种上下文</caption>
 *   <tr><th></th><th>{@link #ISOLATED}（默认）</th><th>{@link #SHARED}</th></tr>
 *   <tr><td>模型看到</td><td>只有本节点的提示词</td><td>此前所有节点、工具调用与结果</td></tr>
 *   <tr><td>事实从哪来</td><td><b>只能靠 {@code ${...}} 显式渲染</b></td><td>历史里"自然带过来"</td></tr>
 *   <tr><td>成本</td><td>不随图变长</td><td>线性增长，逼近预算会触发压缩</td></tr>
 *   <tr><td>可复现</td><td>同输入必然同提示词</td><td>取决于前面跑过什么</td></tr>
 * </table>
 *
 * <p><b>为什么默认隔离？</b> 因为工作流的节点应当是"函数"：事实进、结论出。
 * 共享历史会让一个节点的行为依赖于"之前碰巧跑过哪些节点"，调试时几乎无法复现，
 * 而且长流程必然撞上上下文预算、被压缩掉早期细节 —— 那时候丢的是什么，没人说得清。
 *
 * <p>需要"接着刚才那段话继续聊"时，把它显式设成 {@link #SHARED}。
 * 这是一个需要理由的决定，所以不该是默认值。</p>
 */
public enum NodeContext {

    /** 只看得到本节点自己的提示词；一切上文必须通过 {@code ${...}} 显式传进来。 */
    ISOLATED("isolated"),

    /** 继承此前全部对话（含各节点的提示词、回复与工具结果）。 */
    SHARED("shared");

    private final String wireName;

    NodeContext(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static NodeContext from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ISOLATED;
        }
        return switch (raw.trim().toLowerCase()) {
            case "isolated", "isolate", "独立" -> ISOLATED;
            case "shared", "share", "inherit", "共享", "继承" -> SHARED;
            default -> throw new IllegalArgumentException(
                    "未知的节点上下文 [" + raw + "]，可选：isolated（默认，只带本节点提示词）/ "
                            + "shared（继承整段对话）");
        };
    }
}
