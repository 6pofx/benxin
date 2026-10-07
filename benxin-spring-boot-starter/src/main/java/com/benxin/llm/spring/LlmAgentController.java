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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把已注册的 Agent 暴露成 HTTP 接口，默认<b>关闭</b>（需 {@code llm.web.enabled=true}）。
 *
 * <p>默认关闭是刻意的：这是一个能触发模型调用、并可能间接驱动本地工具执行的入口，
 * 在没有鉴权的情况下不应该因为"引了依赖"就自动对外。</p>
 *
 * <p><b>端点上的几条实际契约</b>（README 里也有一张同样的表，免得只能靠读源码才知道）：</p>
 * <ul>
 *   <li>未知 Agent 名 → <b>404</b>（{@link NoSuchAgentException} 被映射成 HTTP 状态码）；</li>
 *   <li>不传 {@code sessionId} → 每次调用一个独立会话（响应里的 {@code sessionId} 是新生成的），
 *       想续接历史必须显式传；</li>
 *   <li>端点按 <b>Agent 名</b>调用，不经过方法绑定：{@code @SystemPrompt} / {@code @Ctx} /
 *       {@code @User} 这些<b>方法级</b>注解在 HTTP 端点上不生效，只认 Agent 级 {@code systemPrompt}；</li>
 *   <li>失败的运行只广播 {@code onError}，不会产生 {@code agent_run} 行；</li>
 *   <li>{@code @LlmAgent} 不声明 {@code tools} / {@code toolNames} 时继承容器的全局工具表；</li>
 *   <li>SSE 响应头是 {@code text/event-stream;charset=UTF-8}（见 {@link #SSE_CONTENT_TYPE}），
 *       中文事件不会被客户端按 ISO-8859-1 解成乱码。</li>
 * </ul>
 */
@RestController
@RequestMapping("${llm.web.base-path:/llm}")
public class LlmAgentController {

    /**
     * SSE 的响应类型。
     *
     * <p>必须带 {@code charset}：Spring 的 {@code StringHttpMessageConverter} 对 {@code text/*}
     * 在没有 charset 时按 ISO-8859-1 解码，"北京"会变成 "åäº¬"。</p>
     */
    private static final String SSE_CONTENT_TYPE = "text/event-stream;charset=UTF-8";

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

    /**
     * 对话入口。
     *
     * <p>默认返回 JSON；当这个 Agent 打开了流式（{@code @LlmAgent(stream = true)} 或
     * {@code llm.agent.stream=true}）时改为返回 SSE —— 这正是使用者对这两个开关的直觉，
     * 而在 1.1.1 之前它们是**死配置**：写与不写，行为完全一致。</p>
     *
     * <p>想显式选择通道时用 {@code /agents/{name}/stream}（永远 SSE）。</p>
     */
    @PostMapping(value = "/agents/{name}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object chat(@PathVariable String name, @RequestBody ChatPayload payload,
                       HttpServletResponse response) {
        Agent agent = agentRegistry.get(name);
        // 不传 sessionId 时给一次独立的会话，而不是落到 "<agentName>-default" 上：
        // 后者会让"每次调用看似独立"的直觉与"其实共享同一份历史"的行为对不上，
        // 多路调用还会互相串历史。需要续接历史时显式传 sessionId 即可。
        String sessionId = payload.sessionId() == null || payload.sessionId().isBlank()
                ? "http-" + UUID.randomUUID()
                : payload.sessionId();
        if (agent.spec().stream()) {
            response.setContentType(SSE_CONTENT_TYPE);
            return streamResponse(agent, name, payload.message(), sessionId);
        }
        AgentResult result = agent.call(payload.message(), sessionId, null);
        return toMap(result);
    }

    /**
     * 流式对话（SSE）。事件类型：{@code delta} / {@code thinking} / {@code tool_call} /
     * {@code done} / {@code error}。
     *
     * <p>刻意<b>不</b>包含 {@code tool_result}：{@code LlmStreamHandler} 上根本没有对应的回调，
     * 这个事件名发不出来。工具结果只出现在最终 {@code done} 事件里的 {@code toolCalls} 中。</p>
     *
     * <p>响应头由 {@link #SSE_CONTENT_TYPE} 显式写死：Spring 的 {@code StringHttpMessageConverter}
     * 对 {@code text/*} 默认按 ISO-8859-1 解码，不声明 charset 时中文事件到客户端就是乱码。
     * 注意<b>不能</b>只靠 {@code produces = "text/event-stream;charset=UTF-8"}：
     * {@code SseEmitter} 的返回值处理器会把 Content-Type 重新写成不带 charset 的
     * {@code text/event-stream}（实测如此），必须在 response 上直接设置。</p>
     */
    @GetMapping("/agents/{name}/stream")
    public SseEmitter stream(@PathVariable String name,
                            @RequestParam("message") String message,
                            @RequestParam(value = "sessionId", required = false) String sessionId,
                            HttpServletResponse response) {
        response.setContentType(SSE_CONTENT_TYPE);
        Agent agent = agentRegistry.get(name);
        String effectiveSession = sessionId == null || sessionId.isBlank()
                ? "http-" + UUID.randomUUID()
                : sessionId;
        return streamResponse(agent, name, message, effectiveSession);
    }

    /** 把一次运行以 SSE 的形式推给调用方（{@code /stream} 与开了流式的 {@code /chat} 共用）。 */
    private SseEmitter streamResponse(Agent agent, String name, String message, String sessionId) {
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

    /**
     * 未知 Agent 名 → 404（而不是 500）。
     *
     * <p>以前它和所有其它 {@code IllegalArgumentException} 一起被当成服务端错误，
     * 使用者按直觉猜"名字写错了应该是 404"，拿到的却是 500，只能去翻日志。</p>
     */
    @ExceptionHandler(NoSuchAgentException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownAgent(NoSuchAgentException error,
                                                                  HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", HttpStatus.NOT_FOUND.getReasonPhrase());
        body.put("message", error.getMessage());
        body.put("path", request == null ? null : request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
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