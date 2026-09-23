package com.benxin.llm.spring.fixture;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用假模型：让 Spring 集成测试完全不依赖网络。
 *
 * <p>规则只有两条：用户说要"大写"时调用 {@code upper} 工具；其余情况回一段可预测的文本。
 * 简单到不会掩盖真正要验证的东西 —— 也就是"注解声明的接口能不能被正确翻译成一次 Agent 运行"。</p>
 */
public class StubModel implements LlmModel {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile ChatRequest lastRequest;

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.builder().streaming(true).toolCalling(true).build();
    }

    public int calls() {
        return calls.get();
    }

    /** 最近一次收到的请求，便于断言系统提示词渲染、历史续接等行为。 */
    public ChatRequest lastRequest() {
        return lastRequest;
    }

    public String lastSystemPrompt() {
        return lastRequest == null ? null : lastRequest.systemText();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        this.lastRequest = request;
        calls.incrementAndGet();
        String user = lastUserText(request);
        // 只看"最后一条 user 消息之后"的工具结果：历史里更早的工具调用属于上一轮，
        // 真实模型同样不会把它们误当成本轮的观察结果。多轮记忆开启时这一点至关重要。
        List<ChatMessage> currentTurn = messagesAfterLastUser(request);
        boolean toolDone = currentTurn.stream().anyMatch(m -> m.role() == Role.TOOL);

        if (user.contains("大写") && !toolDone && request.hasTools()) {
            return ChatResponse.builder()
                    .message(ChatMessage.assistant("", List.of(
                            new ToolUsePart("call_upper", "upper", "{\"text\":\"" + user + "\"}"))))
                    .finishReason(FinishReason.TOOL_CALLS)
                    .usage(new Usage(10, 5))
                    .build();
        }
        if (toolDone) {
            String observed = currentTurn.stream()
                    .filter(m -> m.role() == Role.TOOL)
                    .map(ChatMessage::text)
                    .reduce("", (a, b) -> a + b);
            return text("工具返回：" + observed);
        }
        // 要求 JSON 时给一段可被 DTO 解析的输出，用于验证强类型返回值
        if (user.contains("JSON")) {
            return text("```json\n{\"message\":\"结构化回答\",\"length\":6}\n```");
        }
        return text("回答：" + user);
    }

    /** 取最后一条 user 消息之后的所有消息，即本轮新增的部分。 */
    private static List<ChatMessage> messagesAfterLastUser(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.subList(i + 1, messages.size());
            }
        }
        return messages;
    }

    private static ChatResponse text(String content) {
        return ChatResponse.builder()
                .message(ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(new Usage(10, 5))
                .build();
    }

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i).text();
            }
        }
        return "";
    }
}