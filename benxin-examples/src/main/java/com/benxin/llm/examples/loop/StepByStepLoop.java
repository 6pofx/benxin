package com.benxin.llm.examples.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.message.ChatMessage;

/**
 * 自定义 Loop 示例：先让模型复述任务，确认理解无误后再进入标准的工具循环。
 *
 * <p>整个类不到 70 行，且<b>没有一行涉及模型调用、工具执行、沙箱校验、流式聚合</b> ——
 * 那些都由 {@link LoopContext} 承担。这就是本心"一切皆可替换"在代码层面的直观体现：
 * 想换一种思考方式，只需要写清"思考的步骤"，不必重新实现一遍基础设施。</p>
 *
 * <p>打上 {@link LlmLoop} 注解后，这个 Loop 会被 starter 自动注册，
 * 于是 {@code @LlmAgent(loop = "step-by-step")} 立刻可用。</p>
 */
@LlmLoop("step-by-step")
public class StepByStepLoop extends AbstractAgentLoop {

    @Override
    public String name() {
        return "step-by-step";
    }

    @Override
    public String description() {
        return "先复述任务确认理解，再进入标准工具循环 —— 自定义 Loop 的最小示例";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        // 第一步：让模型用自己的话复述任务。复杂任务里这一步能显著减少"答非所问"。
        ctx.append(ChatMessage.user("在动手之前，请先用一句话复述你对本任务的理解。"));
        ChatResponse restate = ctx.callModel();
        ctx.emit("step-by-step.understood", restate.text());
        log.info("[step-by-step] 模型复述：{}", restate.text());

        // 第二步：确认理解无误后，交给标准工具循环收尾。
        // 注意 runToolCallingLoop 会自动处理步数预算、工具执行、returnDirect 与收尾封装。
        ctx.append(ChatMessage.user("很好。现在请正式开始执行任务。"));
        return runToolCallingLoop(ctx);
    }
}