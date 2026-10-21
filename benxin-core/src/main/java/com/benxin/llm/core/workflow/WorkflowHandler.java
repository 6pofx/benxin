package com.benxin.llm.core.workflow;

/**
 * 工作流里的 Java 节点：一段由宿主注册、按名字引用的本地逻辑。
 *
 * <p>它补的是另外六种节点都填不上的那个洞 —— {@code set} 只能写静态模板，
 * {@code branch} 只能按表达式选边，而工具（{@code tool} 节点）是<b>给模型用的能力</b>
 * （模型可见、进 Schema、被沙箱与审批管着）。这里要的是"给图用的逻辑"：
 * 读一批值、按业务规则算、吐出结构化结果。</p>
 *
 * <p><b>能力边界（刻意的）</b>：处理器不能调模型、不能调工具。需要模型就用
 * {@code agent} 节点，需要工具就用 {@code tool} 节点 —— 图的可见性与可审计性
 * 正来自于"模型与工具只在被点名的地方出现"。同理，处理器也<b>不能直接指定下一条边</b>：
 * 它只把结论写进变量，路由仍然由出边的条件表达式决定，保证整张图只有一种路由机制。</p>
 *
 * <p><b>信任边界</b>：处理器是宿主自己编译并注册的代码，等同于应用自己的 service，
 * 因此<b>不经过</b> {@code ToolSandbox} 与审批。安全性来自注册制本身 ——
 * 图里只能点名宿主已经装进来的处理器；反射形式的 {@code class}/{@code method} 被刻意排除，
 * 否则一份工作流定义就变成了任意代码执行入口。</p>
 *
 * <p>实现须是无状态或线程安全的：同一个实例会被多张图、多次运行复用。</p>
 */
@FunctionalInterface
public interface WorkflowHandler {

    /**
     * 执行这个节点的逻辑。
     *
     * @param input 只读输入（已渲染的参数 + 黑板快照）
     * @return 节点输出与要写回黑板的变量；不得返回 {@code null}
     * @throws RuntimeException 任何失败都会被引擎转成"节点失败"：按节点的 {@code retry}
     *                          重试，并按 {@code continueOnError} 决定是中断还是走补偿分支
     */
    NodeOutput handle(NodeInput input);
}
