package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.workflow.JavaFlow;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowException;

/**
 * <b>代码编排模式</b>：图由 {@link JavaFlow} 在 Java 里写出来，而不是放在 YAML 里。
 *
 * <p>它和 {@code workflow} 是<b>同一个引擎、同一套语义</b>，只是"谁来写这张图"不同。
 * 因此本类薄到几乎没有逻辑：校验绑定、把定义交给父类、把错误说清楚。</p>
 *
 * <p><b>单向不混用</b>：本 Loop 只接受构造时绑定的 {@link WorkflowDefinition}，
 * <b>刻意不读</b> {@code benxin.workflow} 属性里的 YAML/JSON 文本 ——
 * 那属于声明式模式。混用会让"图从哪来、节点行为怎么解析"两件事都变得含糊，
 * 而这正是两种模式最难排查的地方。</p>
 *
 * <pre>{@code
 * @LlmAgent(loop = "flow")                       // 或程序化装配
 * public interface Reviewer { String review(String code); }
 *
 * Agent agent = Agent.builder("reviewer")
 *         .model("deepseek")
 *         .loop(CodeFlowLoop.of(myFlow()))        // myFlow() 返回 JavaFlow.build()
 *         .build();
 * }</pre>
 *
 * <p>Spring 侧要按名字用，把它注册成 bean 即可：
 * {@code @Bean AgentLoop reviewFlow() { return CodeFlowLoop.of(ReviewFlows.review()); }}</p>
 */
@LlmLoop(CodeFlowLoop.NAME)
public class CodeFlowLoop extends WorkflowLoop {

    /** Loop 名，供 {@code @LlmAgent(loop = "flow")} 引用。 */
    public static final String NAME = "flow";

    /** 未绑定图时的用法提示。 */
    private static final String NO_FLOW =
            "『flow』是代码编排模式：图需要在构造时绑定，不能从配置里读。\n"
                    + "  1) new CodeFlowLoop(JavaFlow.builder(\"名字\")…….build())；\n"
                    + "  2) Agent.builder(...).loop(CodeFlowLoop.of(flow))；\n"
                    + "  3) 或者把它注册成 AgentLoop bean，让 @LlmAgent(loop = \"…\") 按注册名引用。\n"
                    + "  想用 YAML 描述图，请改用 loop=workflow 或 workflow:<名字>。";

    /** 未绑定任何图：注册进 LoopRegistry 供发现，真正运行时给出一条可照做的错误。 */
    public CodeFlowLoop() {
        super(NAME, null, null);
    }

    /** 绑定一张由 {@link JavaFlow} 构建的图。 */
    public CodeFlowLoop(WorkflowDefinition flow) {
        super(NAME, flow, null);
    }

    public static CodeFlowLoop of(WorkflowDefinition flow) {
        return new CodeFlowLoop(flow);
    }

    @Override
    public String description() {
        return definition()
                .map(flow -> "代码编排 [" + flow.name() + "]：拓扑写在 Java 里（"
                        + flow.nodes().size() + " 个节点 / " + flow.edges().size() + " 条边）")
                .orElse("代码编排：拓扑写在 Java 里，节点行为是绑定的对象（需要一个用 JavaFlow 构建的图）");
    }

    /**
     * 只认绑定好的定义。
     *
     * <p>覆盖父类是为了<b>关掉属性通道</b>：{@code loop=flow} 不该被一段 YAML 字符串驱动，
     * 否则"两种模式单向不混用"就成了空话。</p>
     */
    @Override
    protected WorkflowDefinition resolve(LoopContext ctx) {
        return definition().orElseThrow(() -> new WorkflowException(name(), null, NO_FLOW));
    }
}
