package com.benxin.llm.core.loop;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.tool.ToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loop 基类：把"步数预算、模型调用、工具执行、收尾封装"这些每个 Loop 都要写一遍的样板代码
 * 收敛到一处，让具体 Loop 只剩纯粹的思路表达。
 *
 * <p>写一个自定义 Loop 的最小形态：</p>
 * <pre>{@code
 * @LlmLoop("my-loop")
 * public class MyLoop extends AbstractAgentLoop {
 *     @Override
 *     public String name() { return "my-loop"; }
 *
 *     @Override
 *     protected LoopResult doRun(LoopContext ctx) {
 *         return runToolCallingLoop(ctx);   // 直接复用标准骨架
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractAgentLoop implements AgentLoop {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public LoopResult run(LoopContext context) {
        try {
            LoopResult result = doRun(context);
            return result == null ? buildResult(context, "") : result;
        } catch (RuntimeException e) {
            context.listener().onError(e);
            throw e;
        }
    }

    /** 子类实现真正的循环逻辑。 */
    protected abstract LoopResult doRun(LoopContext context);

    // ------------------------------------------------------------------
    // 共用骨架
    // ------------------------------------------------------------------

    /**
     * 标准 tool-calling 循环：调模型 → 有工具调用就执行并继续 → 没有工具调用就收尾。
     * 绝大多数 Agent（含 dsh-minimal、claude-code、codex 的主循环）都是它的变体。
     */
    protected LoopResult runToolCallingLoop(LoopContext ctx) {
        return runToolCallingLoop(ctx, null);
    }

    /**
     * @param beforeEachStep 每步开始前的钩子（可为 null），用于注入提醒、检查预算、更新待办等
     */
    protected LoopResult runToolCallingLoop(LoopContext ctx, StepHook beforeEachStep) {
        while (true) {
            if (outOfBudget(ctx)) {
                return finishTruncated(ctx);
            }
            if (beforeEachStep != null) {
                beforeEachStep.beforeStep(ctx);
            }
            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return buildResult(ctx, "");
            }
            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                return buildResult(ctx, message.text());
            }

            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);

            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return buildResult(ctx, direct.get());
            }
        }
    }

    /** 每步开始前的钩子。 */
    @FunctionalInterface
    public interface StepHook {
        void beforeStep(LoopContext ctx);
    }

    // ------------------------------------------------------------------
    // 常用工具方法
    // ------------------------------------------------------------------

    /** 是否已经用完步数预算。 */
    protected boolean outOfBudget(LoopContext ctx) {
        return ctx.step() >= ctx.maxSteps();
    }

    /** 触达步数上限时的收尾：标记状态并用最后一段模型输出作为答案。 */
    protected LoopResult finishTruncated(LoopContext ctx) {
        markMaxStepsReached(ctx);
        ctx.emit("max-steps-reached", ctx.step());
        return buildResult(ctx, lastAssistantText(ctx));
    }

    /** 若某个工具声明了 {@code returnDirect}，把它的结果直接当作最终答案。 */
    protected Optional<String> directAnswer(LoopContext ctx, List<ToolUsePart> toolUses,
                                            List<ChatMessage> results) {
        for (int i = 0; i < toolUses.size() && i < results.size(); i++) {
            boolean direct = ctx.tools().find(toolUses.get(i).name())
                    .map(ToolCallback::returnDirect)
                    .orElse(Boolean.FALSE);
            if (direct) {
                return Optional.of(results.get(i).text());
            }
        }
        return Optional.empty();
    }

    /** 反向查找最后一条有文本的 assistant 消息。 */
    protected String lastAssistantText(LoopContext ctx) {
        List<ChatMessage> messages = ctx.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == Role.ASSISTANT && !message.text().isBlank()) {
                return message.text();
            }
        }
        return "";
    }

    /** 把历史压成纯文本，供不支持工具调用协议的模型走文本 ReAct。 */
    protected String historyAsText(LoopContext ctx, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : ctx.messages()) {
            if (message.role() == Role.SYSTEM) {
                continue;
            }
            sb.append(message.role().wireName()).append(": ").append(message.text()).append('\n');
            if (sb.length() > maxChars) {
                return sb.substring(Math.max(0, sb.length() - maxChars));
            }
        }
        return sb.toString();
    }

    /** 追加一条 user 角色提醒（用于"你还剩 N 步"一类干预）。 */
    protected void remind(LoopContext ctx, String text) {
        ChatMessage message = ChatMessage.user(text);
        ctx.append(message);
    }

    /** 追加一条 assistant 角色说明（部分模型不支持连续两条 user 消息，用 assistant 过渡）。 */
    protected void note(LoopContext ctx, String text) {
        ctx.append(ChatMessage.assistant(text));
    }

    /** 统一的收尾封装；若上下文是标准实现则复用其累计的用量与工具记录。 */
    protected LoopResult buildResult(LoopContext ctx, String text) {
        if (ctx instanceof DefaultLoopContext standard) {
            return standard.toResult(text);
        }
        return LoopResult.builder()
                .loopName(name())
                .text(text == null ? "" : text)
                .messages(new ArrayList<>(ctx.messages()))
                .steps(ctx.step())
                .build();
    }

    protected void markMaxStepsReached(LoopContext ctx) {
        if (ctx instanceof DefaultLoopContext standard) {
            standard.markMaxStepsReached();
        }
    }

    /** 累计额外用量（例如子代理消耗）。 */
    protected void addUsage(LoopContext ctx, com.benxin.llm.core.chat.Usage usage) {
        if (ctx instanceof DefaultLoopContext standard) {
            standard.addUsage(usage);
        }
    }

    // ------------------------------------------------------------------
    // 文本协议解析（供不支持原生 tool calling 的模型使用）
    // ------------------------------------------------------------------

    private static final Pattern ACTION = Pattern.compile(
            "(?im)^\\s*(?:Action|动作|工具)\\s*[:：]\\s*([A-Za-z0-9_.\\-]+)\\s*$");
    private static final Pattern ACTION_INPUT = Pattern.compile(
            "(?is)(?:Action\\s*Input|动作输入|工具参数)\\s*[:：]\\s*(\\{.*?\\}|\\[.*?]|\".*?\"|[^\\n]*)");
    private static final Pattern FINAL_ANSWER = Pattern.compile(
            "(?is)(?:Final\\s*Answer|最终答案|答案)\\s*[:：]\\s*(.*)$");

    /** 一次文本形式的工具调用。 */
    public record TextAction(String name, String argumentsJson) {
    }

    /** 解析 ReAct 风格的 {@code Action / Action Input}。 */
    protected Optional<TextAction> parseAction(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher action = ACTION.matcher(text);
        if (!action.find()) {
            return Optional.empty();
        }
        String name = action.group(1).trim();
        String args = "{}";
        Matcher input = ACTION_INPUT.matcher(text.substring(action.end()));
        if (input.find()) {
            args = input.group(1).trim();
            if (!args.startsWith("{") && !args.startsWith("[")) {
                // 必须用 Json.write 而不是 writeQuietly：后者对 CharSequence 直接返回原文、
                // 不加引号，拼出来的是 {"input": 北京天气} 这种非法 JSON。
                args = "{\"input\": " + com.benxin.llm.core.util.Json.write(args) + "}";
            }
        }
        return Optional.of(new TextAction(name, args));
    }

    /** 解析 {@code Final Answer: ...}。 */
    protected Optional<String> parseFinalAnswer(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = FINAL_ANSWER.matcher(text);
        if (matcher.find()) {
            String answer = matcher.group(1).trim();
            if (!answer.isEmpty()) {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }

    /** 把工具结果渲染成 ReAct 的 {@code Observation:} 段。 */
    protected String renderObservation(List<ChatMessage> results) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : results) {
            sb.append("Observation: ").append(message.text()).append('\n');
        }
        return sb.toString();
    }

    /** 便于子类抛出的统一异常。 */
    protected ModelException fail(String message) {
        return new ModelException("[" + name() + "] " + message);
    }
}