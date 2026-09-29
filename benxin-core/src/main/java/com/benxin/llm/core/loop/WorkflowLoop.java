package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.core.workflow.WorkflowState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>声明式工作流模式</b>：按一张预先定义的图（YAML / JSON / Java DSL）推进任务。
 *
 * <p>与其它 Loop 的根本差别在于<b>"谁决定下一步"</b>。react、plan-execute 这些 Loop 里，
 * 下一步完全由模型当场决定；而在这里，下一步由<b>图</b>决定 —— 模型只负责填内容，
 * 不负责控制流。这个差别带来三件别处拿不到的东西：</p>
 *
 * <ol>
 *   <li><b>可审计</b>：跑之前就能说出"会经过哪些步骤、在什么条件下走哪条分支"；</li>
 *   <li><b>可复现</b>：同一条输入走的路径是确定的 —— 分支条件是我们写的表达式，
 *       而不是模型对自己进度的自述；</li>
 *   <li><b>可控成本</b>：节点数与每个节点的工具轮次都在图里写死，不存在"模型决定再查十次"。</li>
 * </ol>
 *
 * <p>代价同样明确：<b>图定不下来的时候它帮不上忙</b>。探索性任务应该用 {@code react}；
 * 流程清晰、需要稳定复现的任务才适合它。这也是本心把它做成一个可选 Loop、
 * 而不是替换默认 Loop 的原因。</p>
 *
 * <p>本类只做三件事：取出定义、准备黑板、把引擎跑起来。真正的编排逻辑在
 * {@code com.benxin.llm.core.workflow.WorkflowEngine}，它不依赖任何框架类型。</p>
 *
 * <p>定义来源按优先级：</p>
 * <ol>
 *   <li>{@code ctx.attributes().get("benxin.workflow")} —— 值可以是
 *       {@link WorkflowDefinition}、YAML/JSON 字符串或 {@code Map}；</li>
 *   <li>构造本 Loop 时传入的默认定义。Spring 侧的 {@code llm.workflows.*} 走的正是这条：
 *       每个 YAML 被注册成一个独立的 Loop，形如 {@code workflow:code-review}。</li>
 * </ol>
 */
@LlmLoop(WorkflowLoop.NAME)
public class WorkflowLoop extends AbstractAgentLoop {

    /** Loop 名，供 {@code @LlmAgent(loop = "workflow")} 引用。 */
    public static final String NAME = "workflow";

    /** 运行期传入工作流定义的属性键。 */
    public static final String ATTRIBUTE = "benxin.workflow";

    /** 别名键，语义更明确；两者取到任意一个即可。 */
    public static final String DEFINITION_ATTRIBUTE = "benxin.workflow.definition";

    /** 运行结束后，本次 {@link WorkflowRun} 会写回该属性。 */
    public static final String RUN_ATTRIBUTE = "benxin.workflow.run";

    /** 运行结束后，黑板变量快照会写回该属性。 */
    public static final String VARIABLES_ATTRIBUTE = "benxin.workflow.variables";

    private final WorkflowDefinition definition;
    private final String loopName;

    /** 无默认定义：每次运行都必须从属性里拿到定义。 */
    public WorkflowLoop() {
        this(null, null);
    }

    /** 绑定一个默认定义；Spring 侧每个 {@code llm.workflows.*} 都这样构造一个实例。 */
    public WorkflowLoop(WorkflowDefinition definition) {
        this(null, definition);
    }

    /**
     * @param loopName   显式指定 Loop 名。注册名与 {@code definition.name()} 不一致时（例如
     *                   Spring 侧用配置键当注册名）必须传，否则 {@code /llm/agents} 里
     *                   会出现一个在 {@code /llm/loops} 里查不到的名字
     * @param definition 默认定义，可为 null
     */
    public WorkflowLoop(String loopName, WorkflowDefinition definition) {
        this.definition = definition;
        this.loopName = loopName == null || loopName.isBlank() ? null : loopName;
    }

    public static WorkflowLoop of(WorkflowDefinition definition) {
        return new WorkflowLoop(definition);
    }

    /**
     * 按注册名构造。
     *
     * @param loopName {@code LoopRegistry} 里用的名字（如 {@code workflow:code-review}）
     */
    public static WorkflowLoop named(String loopName, WorkflowDefinition definition) {
        return new WorkflowLoop(loopName, definition);
    }

    /** 构造时绑定的默认定义（可能为空）。 */
    public Optional<WorkflowDefinition> definition() {
        return Optional.ofNullable(definition);
    }

    @Override
    public String name() {
        if (loopName != null) {
            return loopName;
        }
        return definition == null ? NAME : NAME + ":" + definition.name();
    }

    @Override
    public String description() {
        return definition == null
                ? "声明式工作流：按 YAML/JSON 定义的节点与边推进，分支条件由表达式决定（定义来自 benxin.workflow 属性）"
                : "声明式工作流 [" + definition.name() + "]：" + describe(definition);
    }

    private static String describe(WorkflowDefinition definition) {
        String text = definition.description();
        return text == null || text.isBlank()
                ? definition.nodes().size() + " 个节点 / " + definition.edges().size() + " 条边"
                : text;
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        WorkflowDefinition resolved = resolve(ctx);
        WorkflowState state = new WorkflowState(initialInput(ctx));

        log.debug("[workflow:{}] 开始执行，入口节点 [{}]，共 {} 个节点",
                resolved.name(), resolved.resolveEntry().id(), resolved.nodes().size());

        WorkflowRun run = new WorkflowEngine().run(resolved, state, new LoopContextRuntime(ctx));

        Map<String, Object> attributes = ctx.attributes();
        attributes.put(RUN_ATTRIBUTE, run);
        attributes.put(VARIABLES_ATTRIBUTE, state.snapshot());

        log.debug("[workflow:{}] 执行结束，{} 次节点执行，路径 {}",
                resolved.name(), run.executions(), run.visited());

        return buildResult(ctx, run.output());
    }

    /**
     * 决定这次跑哪张图。
     *
     * <p>属性优先于构造时绑定的默认定义 —— 属性是"这一次调用"的意思表示，
     * 默认定义只是"这个 Loop 平时是谁"，前者意图更近，优先级理应更高。</p>
     */
    private WorkflowDefinition resolve(LoopContext ctx) {
        Object raw = ctx.attributes().get(ATTRIBUTE);
        if (raw == null) {
            raw = ctx.attributes().get(DEFINITION_ATTRIBUTE);
        }
        if (raw != null) {
            return coerce(raw);
        }
        if (definition != null) {
            return definition;
        }
        throw new WorkflowException(NAME, null,
                "loop=workflow 需要一份工作流定义，但没有找到。三种提供方式：\n"
                        + "  1) agent.call(input, sessionId, Map.of(\"benxin.workflow\", yamlOrJsonText), stream)；\n"
                        + "  2) 把定义文件放到 llm.workflows.<name>.location，然后用 loop=workflow:<name>；\n"
                        + "  3) 代码里 new WorkflowLoop(definition) 后注册进 LoopRegistry。");
    }

    /** 接受定义对象、YAML/JSON 文本，或已经解析好的 Map 结构。 */
    private WorkflowDefinition coerce(Object raw) {
        if (raw instanceof WorkflowDefinition definition) {
            return definition;
        }
        if (raw instanceof CharSequence text) {
            return WorkflowIo.read(text.toString());
        }
        if (raw instanceof Map<?, ?> map) {
            return Json.mapper().convertValue(map, WorkflowDefinition.class);
        }
        throw new WorkflowException(NAME, null, "无法识别的工作流定义类型："
                + raw.getClass().getName() + "（支持 WorkflowDefinition / YAML 或 JSON 文本 / Map）");
    }

    /**
     * 本次任务的原文。
     *
     * <p>取历史里最后一条 user 消息 —— 也就是调用方传进来的那个 input。
     * 用历史而不是给 {@link LoopContext} 加一个方法，是为了不动全框架共用的接口契约：
     * 一个可选 Loop 不该为了自己方便去改所有人都在实现的接口。</p>
     */
    private String initialInput(LoopContext ctx) {
        List<ChatMessage> messages = ctx.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == Role.USER && !message.text().isBlank()) {
                return message.text();
            }
        }
        return "";
    }
}
