package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.Assistant;
import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code @LlmAgent} 接口的动态代理处理器：把一次普通的 Java 方法调用翻译成一次 Agent 运行。
 *
 * <p>方法参数的语义由注解决定：</p>
 * <ul>
 *   <li>无注解 或 {@link User} → user 消息</li>
 *   <li>{@link Assistant} → 预填充的 assistant 消息（少样本引导）</li>
 *   <li>{@link Ctx} → 只作为模板变量与运行属性，不进消息体</li>
 *   <li>{@link Memory} → 会话 id</li>
 *   <li>{@link LlmStreamHandler} 类型 → 流式增量出口</li>
 * </ul>
 *
 * <p>返回值按声明类型自适应：{@code String} / {@link AgentResult} / {@code void} /
 * {@code Optional<String>} / {@code List<ChatMessage>}，其余类型视为 DTO ——
 * 会把模型输出当 JSON 反序列化成该类型，这让"声明式取值"变得非常自然。</p>
 */
public class LlmAgentInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentInvocationHandler.class);
    private static final TemplateSystemPromptProvider TEMPLATES = new TemplateSystemPromptProvider();

    private final Class<?> agentInterface;
    private final String agentName;
    private final LlmAgentFactory factory;
    private final AgentRegistry agentRegistry;
    private final Map<Method, MethodBinding> bindings = new ConcurrentHashMap<>();

    private volatile Agent agent;

    public LlmAgentInvocationHandler(Class<?> agentInterface, String agentName,
                                     LlmAgentFactory factory, AgentRegistry agentRegistry) {
        this.agentInterface = agentInterface;
        this.agentName = agentName;
        this.factory = factory;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 拿到（并缓存）本接口对应的 Agent。
     *
     * <p>名字的登记不在这里做：{@code LlmAgentFactoryBean} 在创建代理时就把
     * {@code agentName → 本方法} 注册成惰性条目，于是"从未被调用过"的 Agent
     * 也能出现在 {@code /llm/agents} 里、也能被子代理按名解析到。</p>
     */
    Agent agent() {
        Agent current = agent;
        if (current == null) {
            synchronized (this) {
                current = agent;
                if (current == null) {
                    current = factory.createFromInterface(agentInterface);
                    agent = current;
                }
            }
        }
        return current;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "@LlmAgent(" + agentInterface.getName() + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
        if (method.isDefault()) {
            throw new LlmAgentInvocationException(
                    "接口默认方法 " + method + " 暂不受支持，请改为在实现类中提供");
        }

        MethodBinding binding = bindings.computeIfAbsent(method, m -> MethodBinding.of(agentInterface, m));

        Map<String, Object> attributes = new LinkedHashMap<>();
        List<ChatMessage> messages = new ArrayList<>();
        Map<String, Object> templateVars = new LinkedHashMap<>();
        String sessionId = null;
        LlmStreamHandler streamHandler = null;

        for (ParamBinding param : binding.params()) {
            Object value = args == null || param.index() >= args.length ? null : args[param.index()];
            switch (param.kind()) {
                case USER -> {
                    messages.add(ChatMessage.user(param.prefix() + stringify(value) + param.suffix()));
                    // 普通参数也进模板变量，这样 @SystemPrompt 里可以直接写 {question}，
                    // 而不必为了引用同一个值再多加一个 @Ctx 参数。
                    if (param.name() != null) {
                        templateVars.put(param.name(), value);
                    }
                }
                case ASSISTANT -> {
                    messages.add(ChatMessage.assistant(stringify(value)));
                    if (param.name() != null) {
                        templateVars.put(param.name(), value);
                    }
                }
                case CTX -> {
                    templateVars.put(param.name(), value);
                    if (param.attribute()) {
                        attributes.put(param.name(), value);
                    }
                }
                case MEMORY -> sessionId = value == null ? null : String.valueOf(value);
                case STREAM -> streamHandler = value instanceof LlmStreamHandler handler ? handler : null;
                case IGNORE -> {
                    // 无参语义，忽略
                }
            }
        }

        Agent target = agent();
        String systemPrompt = binding.resolveSystemPrompt(templateVars, target.spec().systemPrompt());
        if (systemPrompt != null) {
            attributes.put(com.benxin.llm.core.agent.AgentAttributes.SYSTEM_PROMPT, systemPrompt);
        }

        if (log.isDebugEnabled()) {
            log.debug("[benxin] 调用 {}.{} → Agent [{}]，消息 {} 条，会话 {}",
                    agentInterface.getSimpleName(), method.getName(), agentName, messages.size(), sessionId);
        }

        AgentResult result = target.call(sessionId, messages, attributes, streamHandler);
        return adapt(method, result);
    }

    /** 把 Agent 结果适配成方法声明的返回类型。 */
    private Object adapt(Method method, AgentResult result) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType == String.class || returnType == CharSequence.class) {
            return result.text();
        }
        if (returnType == AgentResult.class) {
            return result;
        }
        if (returnType == Optional.class) {
            return Optional.ofNullable(result.text());
        }
        if (List.class.isAssignableFrom(returnType)) {
            return result.messages();
        }
        if (returnType == int.class || returnType == Integer.class) {
            return result.steps();
        }
        if (returnType == long.class || returnType == Long.class) {
            return result.durationMillis();
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return !result.text().isBlank();
        }
        // 其余类型按"模型输出即 JSON"处理，让声明式取值真正好用
        try {
            ObjectMapper mapper = Json.mapper();
            return mapper.readValue(extractJson(result.text()), returnType);
        } catch (Exception e) {
            throw new LlmAgentInvocationException("方法返回类型为 " + returnType.getSimpleName()
                    + "，但模型输出无法解析为该类型。模型原始输出："
                    + Json.abbreviate(result.text()), e);
        }
    }

    /** 容忍模型习惯性包裹的 ```json 代码围栏与前后闲聊。 */
    static String extractJson(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int start = trimmed.indexOf('\n', fence);
            int end = trimmed.indexOf("```", start < 0 ? fence + 3 : start);
            if (start > 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }
        int brace = trimmed.indexOf('{');
        int bracket = trimmed.indexOf('[');
        int begin = brace < 0 ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (begin > 0) {
            int lastBrace = trimmed.lastIndexOf('}');
            int lastBracket = trimmed.lastIndexOf(']');
            int end = Math.max(lastBrace, lastBracket);
            if (end > begin) {
                trimmed = trimmed.substring(begin, end + 1);
            }
        }
        return trimmed;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return Json.writeQuietly(value);
    }

    // ------------------------------------------------------------------
    // 方法绑定分析
    // ------------------------------------------------------------------

    enum ParamKind {
        USER, ASSISTANT, CTX, MEMORY, STREAM, IGNORE
    }

    record ParamBinding(int index, ParamKind kind, String name, String prefix, String suffix,
                        boolean attribute) {
    }

    record MethodBinding(String systemTemplate, List<String> append, boolean inherit,
                         List<ParamBinding> params) {

        static MethodBinding of(Class<?> agentInterface, Method method) {
            SystemPrompt annotation = method.getAnnotation(SystemPrompt.class);
            String template = annotation == null ? null : blankToNull(annotation.value());
            List<String> append = annotation == null ? List.of() : List.of(annotation.append());
            boolean inherit = annotation == null || annotation.inherit();

            List<ParamBinding> params = new ArrayList<>();
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                params.add(bind(i, parameter));
            }
            return new MethodBinding(template, append, inherit, List.copyOf(params));
        }

        private static ParamBinding bind(int index, Parameter parameter) {
            if (LlmStreamHandler.class.isAssignableFrom(parameter.getType())) {
                return new ParamBinding(index, ParamKind.STREAM, null, "", "", true);
            }
            Annotation[] annotations = parameter.getAnnotations();
            for (Annotation annotation : annotations) {
                if (annotation instanceof User user) {
                    return new ParamBinding(index, ParamKind.USER, parameter.getName(),
                            user.prefix(), user.suffix(), true);
                }
                if (annotation instanceof Assistant) {
                    return new ParamBinding(index, ParamKind.ASSISTANT, parameter.getName(), "", "", true);
                }
                if (annotation instanceof Ctx ctx) {
                    String name = !ctx.value().isBlank() ? ctx.value() : parameter.getName();
                    return new ParamBinding(index, ParamKind.CTX, name, "", "", ctx.attribute());
                }
                if (annotation instanceof Memory) {
                    return new ParamBinding(index, ParamKind.MEMORY, nullConstant(), "", "", true);
                }
            }
            // 无注解参数默认作为 user 内容 —— 让"`String chat(String q)`"这种最朴素的写法可用
            return new ParamBinding(index, ParamKind.USER, parameter.getName(), "", "", true);
        }

        private static String nullConstant() {
            return null;
        }

        /**
         * 渲染方法级系统提示词；返回 null 表示"本方法没有声明，交给 Agent 级
         * {@link com.benxin.llm.core.prompt.SystemPromptProvider} 决定"。
         *
         * <p>后一点很重要：如果方法没有 {@code @SystemPrompt} 却仍然在这里生成一份
         * 与 Agent 级相同的文本并作为覆盖值下发，用户自定义的 SystemPromptProvider
         * 就会在声明式路径上被静默绕过。</p>
         */
        String resolveSystemPrompt(Map<String, Object> vars, String agentPrompt) {
            boolean declared = systemTemplate != null
                    || append.stream().anyMatch(s -> s != null && !s.isBlank());
            if (!declared) {
                return null;
            }
            List<String> pieces = new ArrayList<>();
            if (systemTemplate != null) {
                pieces.add(systemTemplate);
            }
            append.stream().filter(s -> s != null && !s.isBlank()).forEach(pieces::add);
            if (inherit && agentPrompt != null && !agentPrompt.isBlank()) {
                pieces.add(agentPrompt);
            }
            String joined = String.join("\n\n", pieces);
            return TEMPLATES.render(joined, null, vars);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}