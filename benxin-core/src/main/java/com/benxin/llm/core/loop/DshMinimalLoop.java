package com.benxin.llm.core.loop;

import com.benxin.llm.core.annotation.LlmLoop;

/**
 * DSH 极简循环 —— 「一切皆可替换」的最小可读样板。
 *
 * <p><b>设计来源</b>：DSH（DeepSeek Harness）式的一问一答主循环。它只保留 Agent 之所以是
 * Agent 的最小内核：把历史交给模型 → 模型要么给出答案、要么请求工具 → 执行工具并把结果放回
 * 历史 → 再问一次，直到模型不再请求工具。整条主线没有分支、没有阶段、没有额外状态。</p>
 *
 * <p><b>它为什么存在</b>：本心的主张是"框架不替使用者做决定"。计划、反思、上下文压缩、子代理、
 * 内置工具集都不是 Loop 的必需品，而是可以另行替换的协作组件。这个类把最小形态摊开给人看 ——
 * 一个能用的 Agent Loop 确实只有几行代码；其余能力全部通过替换 {@code ContextManager} /
 * {@code ToolRegistry} / {@code SystemPromptProvider} / {@code AgentInterceptor} 获得。
 * 想改造 Agent 的思考方式、又不想被框架的既有假设绑住时，从这个类复制一份开始最省事。</p>
 *
 * <p><b>取舍</b>：</p>
 * <ul>
 *   <li>不压缩上下文 —— 交给 {@code ContextManager}，Loop 只负责"什么时候调模型"。</li>
 *   <li>不裁剪工具、不截断工具结果 —— 交给 {@code ToolRegistry} 与 {@code ToolSandbox}。</li>
 *   <li>除基类的步数预算外不加任何终止条件，也不做重试、降级、协议兜底。</li>
 *   <li>因此它适合"模型听话、工具齐备"的场景；需要兼容不支持原生工具调用的模型请换
 *       {@code react}，需要自我纠错请换 {@code reflexion}。</li>
 * </ul>
 *
 * <p>本类刻意保持极短（正文不到 25 行），任何"顺手加一点功能"的改动都应当先考虑
 * 放到别的 Loop 或别的协作组件里。</p>
 */
@LlmLoop(value = "dsh-minimal", defaultLoop = false)
public class DshMinimalLoop extends AbstractAgentLoop {

    @Override
    public String name() {
        return "dsh-minimal";
    }

    @Override
    public String description() {
        return "DSH 极简循环：调模型 → 执行工具 → 再调模型，直到模型不再请求工具。"
                + "零内置工具、零上下文压缩，一切靠外部替换。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        return runToolCallingLoop(ctx);
    }
}
