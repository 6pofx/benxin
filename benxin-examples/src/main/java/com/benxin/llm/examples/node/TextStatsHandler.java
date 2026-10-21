package com.benxin.llm.examples.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流 Java 节点示例：给一段文本算篇幅，并把它变成后续节点可用的变量。
 *
 * <p>它演示了 Java 节点该被用在哪：<b>一段确定性的、与模型无关的逻辑</b>。
 * 数一遍字数、判一下长短，既不需要模型，也不该为它写一条提示词 ——
 * 如果用 {@code agent} 节点做这件事，既慢又不可靠；用 {@code tool} 节点则得先把它
 * 注册成模型可见的能力，而模型根本不需要知道"怎么数字数"。</p>
 *
 * <p>{@code @LlmNode("text-stats")} 里的名字就是工作流里 {@code handler:} 引用的名字。
 * 该 bean 由本心的自动配置收集进 {@code WorkflowHandlerRegistry}；定义里写错名字会在
 * <b>启动期</b>报错并列出已注册清单。</p>
 */
@Component
@LlmNode("text-stats")
public class TextStatsHandler implements WorkflowHandler {

    /** 超过这个字数就提示"偏长"，只是示例阈值。 */
    private static final int LONG_ENOUGH = 400;

    @Override
    public NodeOutput handle(NodeInput input) {
        String text = input.arg("text");
        int chars = text.strip().length();
        long nonBlankLines = text.isBlank() ? 0 : text.lines().filter(line -> !line.isBlank()).count();
        String lengthLevel = chars > LONG_ENOUGH ? "偏长" : "合适";

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("charCount", chars);
        variables.put("lineCount", nonBlankLines);
        variables.put("lengthLevel", lengthLevel);

        String summary = "字数 " + chars + " / 有效行 " + nonBlankLines + "（" + lengthLevel + "）";
        return NodeOutput.of(summary, variables);
    }
}
