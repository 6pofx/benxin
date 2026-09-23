package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把已注册的 Agent 暴露成 HTTP 接口，默认<b>关闭</b>（需 {@code llm.web.enabled=true}）。
 *
 * <p>默认关闭是刻意的：这是一个能触发模型调用、并可能间接驱动本地工具执行的入口，
 * 在没有鉴权的情况下不应该因为"引了依赖"就自动对外。</p>
 */
@RestController
@RequestMapping("${llm.web.base-path:/llm}")
public class LlmAgentController {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentController.class);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "benxin-sse-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final AgentRegistry agentRegistry;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;

    public LlmAgentController(AgentRegistry agentRegistry, ModelRegistry modelRegistry, LoopRegistry loopRegistry) {
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
    }

    /** 列出全部 Agent 及其装配情况。 */
    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : agentRegistry.names()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            agentRegistry.find(name).ifPresent(agent -> {
                item.put("loop", agent.loop().name());
                item.put("model", agent.model().name());
                item.put("tools", agent.tools().names());
                item.put("maxSteps", agent.spec().maxSteps());
                item.put("systemPrompt", agent.spec().systemPrompt());
            });
            result.add(item);
        }
        return result;
    }

    @GetMapping("/loops")
    public List<String> loops() {
        return loopRegistry.descriptions();
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("default", modelRegistry.defaultName());
        result.put("names", modelRegistry.names());
        return result;
    }

    /** 同步对话。 */
    @PostMapping(value = "/agents/{name}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@PathVariable String name, @RequestBody ChatPayload payload) {
        Agent agent = agentRegistry.get(name);
        AgentResult result = agent.call(payload.message(), payload.sessionId(), null);
        return toMap(result);
    }

    /**
     * 流式对话（SSE）。事件类型：{@code delta} / {@code thinking} / {@code tool_call} /
     * {@code tool_result} / {@code done} / {@code error}。
     */
    @GetMapping(value = "/agents/{name}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String name,
                            @RequestParam("message") String message,
                            @RequestParam(value = "sessionId", required = false) String sessionId) {
        Agent agent = agentRegistry.get(name);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());

        CompletableFuture.runAsync(() -> {
            try {
                AgentResult result = agent.call(message, sessionId, new SseForwarder(emitter));
                send(emitter, "done", toMap(result));
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("[benxin] /agents/{}/stream 执行失败", name, e);
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.complete();
            }
        }, EXECUTOR);
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开是常态，静默忽略
        }
    }

    private static Map<String, Object> toMap(AgentResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agent", result.agentName());
        map.put("sessionId", result.sessionId());
        map.put("text", result.text());
        map.put("steps", result.steps());
        map.put("loop", result.loopName());
        map.put("model", result.modelName());
        map.put("durationMillis", result.durationMillis());
        map.put("finishReason", String.valueOf(result.finishReason()));
        map.put("maxStepsReached", result.maxStepsReached());
        Usage usage = result.usage();
        map.put("usage", Map.of(
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "totalTokens", usage.totalTokens()));
        map.put("toolCalls", result.toolCalls());
        return map;
    }

    /** 请求体。 */
    public record ChatPayload(String message, String sessionId) {
        public ChatPayload {
            message = message == null ? "" : message;
        }
    }

    /** 把 Agent 的流式增量转成 SSE 事件。 */
    private static final class SseForwarder implements LlmStreamHandler {

        private final SseEmitter emitter;

        private SseForwarder(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onTextDelta(String delta) {
            send(emitter, "delta", Map.of("text", delta == null ? "" : delta));
        }

        @Override
        public void onThinkingDelta(String delta) {
            send(emitter, "thinking", Map.of("text", delta == null ? "" : delta));
        }

        @Override
        public void onToolCall(com.benxin.llm.core.message.ToolUsePart toolUse) {
            send(emitter, "tool_call", Map.of("name", toolUse.name(), "arguments", toolUse.argumentsJson()));
        }
    }

    /** 供内部调试用的"事件直通"监听器工厂（当前未使用，保留为扩展点）。 */
    static AgentListener eventLogger() {
        return new AgentListener() {
            @Override
            public void onToolResult(ToolInvocation invocation, ToolResult result) {
                log.debug("[benxin] 工具 {} 完成，错误={}", invocation.toolName(), result.error());
            }

            @Override
            public void onAgentEnd(AgentResult result) {
                log.debug("[benxin] Agent {} 结束，输出 {} 字符", result.agentName(), result.text().length());
            }
        };
    }

    static List<ChatMessage> emptyHistory() {
        return List.of();
    }
}