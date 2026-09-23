package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 循环（Thought → Action → Observation），同时覆盖两类模型。
 *
 * <p><b>设计来源</b>：Yao 等人 2022 年的 ReAct —— 让模型交替产出"推理"与"行动"，
 * 再用真实环境的观察修正推理链，比纯思维链更不容易在长任务里跑偏。</p>
 *
 * <p><b>为什么需要文本协议兜底</b>：ReAct 的结构化实现依赖厂商的原生 function calling
 * （OpenAI 的 {@code tool_calls}、Anthropic 的 {@code tool_use}、Gemini 的
 * {@code functionCall}）。但本心面对的是"任何 OpenAI 兼容网关 + 本地推理模型"的现实环境：
 * 不少模型没有工具调用能力，不少网关也不透传工具字段。若只会走结构化路径，这个 Loop 在这些
 * 场景下就直接不可用。因此这里保留一条文本协议路径 —— 用提示词约定
 * {@code Action / Action Input / Final Answer} 格式，靠基类已经写好的
 * {@link AbstractAgentLoop#parseAction(String)} / {@link AbstractAgentLoop#parseFinalAnswer(String)}
 * 解析，工具结果以 {@code Observation:} 的 user 消息回灌。代价是格式遵从度不如原生协议
 * （模型可能写错关键字），所以只在模型确实不支持时才降级，也允许用 {@code forceTextProtocol}
 * 手动开关做对照实验。</p>
 *
 * <p><b>其他取舍</b>：</p>
 * <ul>
 *   <li>格式说明注入在第一步的 user 消息里，而不是系统提示词：系统提示在本 Loop 执行前
 *       就已渲染完成（{@code ctx.systemPrompt()} 是快照），往 {@code ctx.attributes()} 里写
 *       也来不及影响它，唯一可靠的注入点就是一条新 user 消息；代价是它占用一段上下文预算。</li>
 *   <li>文本协议下工具不存在、参数不是合法 JSON、工具执行抛异常，一律转成
 *       {@code Observation} 回灌，绝不向上抛 —— 文本协议里模型没有结构化错误通道，
 *       把错误原样告诉它，通常能让它自己换个工具或改参数。</li>
 *   <li>参数解析多一层修复：模型写裸文本（{@code Action Input: 北京天气}）时，基类的
 *       {@code parseAction} 会把它包成不带引号的 {@code {"input": 北京天气}}，即非法 JSON；
 *       这里识别这种包装并剥掉，把原文作为 {@code input} 交给工具。</li>
 *   <li>历史保持完整：每轮走 {@code ctx.callModel()} 后靠追加消息驱动，不把历史整段压成文本
 *       再发。只有历史里已经存在结构化工具消息（{@code role=tool} 或 tool_use 块）——
 *       文本协议模型多半渲染不了它们 —— 才在开场白里补一段扁平化转录帮它建立上下文。</li>
 *   <li>{@code maxIterations} 收得比 {@code ctx.maxSteps()} 更紧时，结构化路径用一个私有信号
 *       从 {@link StepHook} 里跳出标准骨架：基类钩子只能"介入"不能"中止"，这比对子类重写一遍
 *       工具调用骨架更不容易出错。</li>
 * </ul>
 */
@LlmLoop("react")
public class ReActLoop extends AbstractAgentLoop {

    /** 文本协议路径补充扁平化转录时的长度上限（字符）。 */
    private static final int TRANSCRIPT_LIMIT = 12_000;

    /** 匹配 {@code {"input": 任意内容}} 包装，允许内容没有引号（基类对裸文本参数的包装形式）。 */
    private static final Pattern BARE_INPUT_WRAPPER =
            Pattern.compile("^\\{\\s*\"input\"\\s*:\\s*(.*?)\\s*}$", Pattern.DOTALL);

    private static final String PROTOCOL_SPEC = """
            你现在要用 ReAct 的方式逐步完成任务：每一轮只输出下面两种格式之一，不要输出多余内容。

            一、需要调用工具时，分三行输出：
            Thought: <你的推理>
            Action: <工具名，本行只写工具名，不要带参数>
            Action Input: <该工具参数的 JSON，例如 {"path": "a.txt"}>

            二、已经可以给出最终答案时，只输出一行：
            Final Answer: <最终答案>

            工具的执行结果我会以 "Observation: ..." 的形式发给你，请据此继续下一轮。
            请严格使用 Action / Action Input / Final Answer 这三个英文关键字，不要自造格式。
            """;

    private static final String FORMAT_CORRECTION = """
            你上一条回复里既没有解析出 Action，也没有解析出 Final Answer。请严格按下面的格式重新回复，不要解释原因。

            需要调用工具时：
            Thought: <推理>
            Action: <工具名>
            Action Input: <JSON 参数>

            已经可以回答时：
            Final Answer: <最终答案>
            """;

    private final int maxIterations;
    private final boolean forceTextProtocol;

    public ReActLoop() {
        this(0, false);
    }

    /**
     * @param maxIterations     本 Loop 自己的迭代上限；{@code <= 0} 表示沿用 {@code ctx.maxSteps()}
     * @param forceTextProtocol 即使模型声明支持原生 tool calling 也强制走文本协议（联调与对照实验用）
     */
    public ReActLoop(int maxIterations, boolean forceTextProtocol) {
        this.maxIterations = maxIterations;
        this.forceTextProtocol = forceTextProtocol;
    }

    @Override
    public String name() {
        return "react";
    }

    @Override
    public String description() {
        return "ReAct 循环（Thought → Action → Observation）：模型支持原生 function calling 时走结构化工具调用，"
                + "不支持时自动降级为 Action / Action Input / Final Answer 文本协议，兼容没有工具调用能力的模型。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        if (nativeToolCalling(ctx)) {
            return maxIterations > 0 ? runStructuredWithBudget(ctx) : runToolCallingLoop(ctx);
        }
        return runTextProtocol(ctx);
    }

    // ------------------------------------------------------------------
    // 结构化路径（模型支持原生 tool calling）
    // ------------------------------------------------------------------

    private boolean nativeToolCalling(LoopContext ctx) {
        return !forceTextProtocol && ctx.model() != null && ctx.model().capabilities().toolCalling();
    }

    /** 结构化路径 + 更紧的迭代预算；到达上限时按截断收尾，语义与基类一致。 */
    private LoopResult runStructuredWithBudget(LoopContext ctx) {
        AtomicInteger used = new AtomicInteger();
        try {
            return runToolCallingLoop(ctx, c -> {
                if (used.incrementAndGet() > maxIterations) {
                    throw IterationBudgetReached.INSTANCE;
                }
            });
        } catch (IterationBudgetReached reached) {
            log.debug("[react] 结构化路径达到 maxIterations={} 上限，截断收尾", maxIterations);
            return finishTruncated(ctx);
        }
    }

    /** 供 {@link StepHook} 跳出标准骨架的内部信号。不记录栈帧：它只用于控制流，不是错误。 */
    private static final class IterationBudgetReached extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private static final IterationBudgetReached INSTANCE = new IterationBudgetReached();

        private IterationBudgetReached() {
            super(null, null, false, false);
        }
    }

    // ------------------------------------------------------------------
    // 文本协议路径（模型没有原生 tool calling）
    // ------------------------------------------------------------------

    private LoopResult runTextProtocol(LoopContext ctx) {
        int budget = effectiveIterations(ctx);
        // 系统提示已成定局，只能在第一步用一条 user 消息把协议交给模型
        remind(ctx, protocolPrimer(ctx));

        for (int round = 0; round < budget; round++) {
            if (outOfBudget(ctx)) {
                return finishTruncated(ctx);
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return buildResult(ctx, "");
            }
            String text = message.text();

            Optional<String> finalAnswer = parseFinalAnswer(text);
            if (finalAnswer.isPresent()) {
                return buildResult(ctx, finalAnswer.get());
            }

            Optional<TextAction> parsed = parseAction(text);
            if (parsed.isPresent()) {
                TextAction action = parsed.get();
                log.debug("[react] 文本协议第 {} 轮调用工具 [{}]", round + 1, action.name());
                remind(ctx, "Observation: " + executeTextAction(ctx, action));
                continue;
            }

            // 两种格式都没解析出来：把格式再讲一遍，让模型自我纠正后重试
            log.debug("[react] 文本协议第 {} 轮未解析出 Action / Final Answer，追加格式纠正提示", round + 1);
            remind(ctx, FORMAT_CORRECTION);
        }
        return finishTruncated(ctx);
    }

    private int effectiveIterations(LoopContext ctx) {
        int maxSteps = ctx.maxSteps() > 0 ? ctx.maxSteps() : 1;
        return maxIterations > 0 ? Math.max(1, Math.min(maxIterations, maxSteps)) : maxSteps;
    }

    /**
     * 执行一次文本协议工具调用，并把任何失败都变成一段可以回灌的 Observation。
     *
     * <p>文本协议下模型没有结构化错误通道：抛异常会直接炸掉整个任务，而把"工具不存在、
     * 可用工具是哪些"告诉它，通常下一轮它就会改对。因此这里只回灌、不抛出。</p>
     */
    private String executeTextAction(LoopContext ctx, TextAction action) {
        if (ctx.tools() == null || ctx.tools().find(action.name()).isEmpty()) {
            log.debug("[react] 模型请求了不存在的工具 [{}]", action.name());
            return "错误：不存在名为 [" + action.name() + "] 的工具，可用工具: "
                    + (ctx.tools() == null ? "[]" : ctx.tools().names());
        }
        try {
            ToolResult result = ctx.callTool(action.name(), argumentsOf(action.argumentsJson()));
            if (result == null) {
                return "错误：工具 [" + action.name() + "] 没有返回任何结果，请换一种方式重试";
            }
            return result.error() ? "错误：" + result.content() : result.content();
        } catch (RuntimeException e) {
            log.debug("[react] 文本协议工具调用失败: {}", e.toString());
            return "错误：工具 [" + action.name() + "] 执行失败 -> " + e.getMessage();
        }
    }

    /** 把 Action Input 文本转成工具参数；不是合法 JSON 对象时尽力还原成 {@code {"input": 原文}}。 */
    private Map<String, Object> argumentsOf(String argumentsJson) {
        JsonNode node = Json.parseQuietly(argumentsJson);
        if (node != null && node.isObject()) {
            return Json.toMap(node);
        }
        String raw = argumentsJson == null ? "" : argumentsJson.trim();
        if (node != null && node.isValueNode()) {
            raw = node.asText();
        }
        // 模型写的是裸文本（Action Input: 北京天气）时，基类的 parseAction 会把它包成
        // {"input": 原文}，而原文没有加引号，于是这段包装本身不是合法 JSON。
        // 这里把包装剥掉，把原文当作 input 交给工具，免得把语法残渣喂给工具函数。
        Matcher wrapper = BARE_INPUT_WRAPPER.matcher(raw);
        if (wrapper.matches()) {
            return Map.of("input", unquote(wrapper.group(1)));
        }
        return Map.of("input", raw);
    }

    private static String unquote(String text) {
        String value = text.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 开场白：协议说明 + 可用工具清单 + （必要时）扁平化转录。 */
    private String protocolPrimer(LoopContext ctx) {
        String toolSection;
        if (ctx.tools() == null || ctx.tools().isEmpty()) {
            toolSection = "当前没有可用工具，请直接推理并给出 Final Answer。";
        } else {
            StringJoiner joiner = new StringJoiner("\n");
            for (ToolCallback callback : ctx.tools().all()) {
                joiner.add("- " + callback.name() + "：" + abbreviate(callback.description(), 200));
            }
            toolSection = "可用工具（Action 行只能填下列名字之一）：\n" + joiner;
        }
        return PROTOCOL_SPEC + "\n" + toolSection + transcript(ctx);
    }

    /**
     * 历史里若已存在结构化工具消息，说明它可能来自上一轮的原生工具调用或者别的 Loop，
     * 文本协议模型多半渲染不了（甚至网关会直接报错），这里补一段扁平化转录帮它建立上下文；
     * 历史本来就是纯文本时不额外重复。
     */
    private String transcript(LoopContext ctx) {
        boolean structured = ctx.messages().stream()
                .anyMatch(m -> m.role() == Role.TOOL || m.hasToolUse());
        if (!structured) {
            return "";
        }
        return "\n\n以下是到目前为止的对话记录（纯文本形式，供你建立上下文）：\n"
                + historyAsText(ctx, TRANSCRIPT_LIMIT);
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
