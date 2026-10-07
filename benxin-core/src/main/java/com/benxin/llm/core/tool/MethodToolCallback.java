package com.benxin.llm.core.tool;

import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把一个 {@code @LlmTool} 方法包装成 {@link ToolCallback}。
 *
 * <p>负责三件事：参数 JSON → Java 类型的绑定、框架注入参数（{@link ToolContext} /
 * {@link AgentSession}）的填充、以及返回值 → 文本的序列化。所有异常都被转成
 * {@link ToolResult#error}，让模型有机会自我纠正，而不是把整条 Agent 链路炸掉。</p>
 */
public class MethodToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(MethodToolCallback.class);

    private final Object bean;
    private final Method method;
    private final ToolSpec spec;
    private final boolean requiresApproval;
    private final boolean parallelSafe;

    public MethodToolCallback(Object bean, Method method) {
        this.bean = bean;
        this.method = method;
        this.method.setAccessible(true);
        this.spec = buildSpec(method);
        LlmTool methodAnnotation = method.getAnnotation(LlmTool.class);
        LlmTool classAnnotation = method.getDeclaringClass().getAnnotation(LlmTool.class);
        // 类与方法都标注时取"安全侧"：审批任一处要就生效，并行任一处禁就生效。
        this.requiresApproval = (methodAnnotation != null && methodAnnotation.requiresApproval())
                || (classAnnotation != null && classAnnotation.requiresApproval());
        this.parallelSafe = (methodAnnotation == null || methodAnnotation.parallelSafe())
                && (classAnnotation == null || classAnnotation.parallelSafe());
    }

    public Method method() {
        return method;
    }

    public Object bean() {
        return bean;
    }

    private static ToolSpec buildSpec(Method method) {
        LlmTool annotation = method.getAnnotation(LlmTool.class);
        LlmTool classAnnotation = method.getDeclaringClass().getAnnotation(LlmTool.class);

        String name = null;
        String description = null;
        boolean returnDirect = false;
        if (annotation != null) {
            name = firstNonBlank(annotation.name(), annotation.value());
            description = annotation.description();
            returnDirect = annotation.returnDirect();
        }
        if (name == null && classAnnotation != null) {
            name = firstNonBlank(classAnnotation.name(), classAnnotation.value());
        }
        if (description == null || description.isBlank()) {
            description = classAnnotation == null ? "" : classAnnotation.description();
        }
        if (name == null || name.isBlank()) {
            name = method.getName();
        }
        return new ToolSpec(name, description == null ? "" : description,
                JsonSchemaGenerator.forMethod(method), returnDirect);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public boolean requiresApproval() {
        return requiresApproval;
    }

    @Override
    public boolean parallelSafe() {
        return parallelSafe;
    }

    /**
     * 执行工具方法。
     *
     * <p>先做一次必填参数校验：schema 里写了 {@code required} 却不在运行期强制，
     * 模型漏参时业务代码会拿到 {@code null} / {@code 0} 而不是"你漏了参数"这个信号，
     * 既无法自我保护也无法自我纠正。</p>
     */
    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (Parameter parameter : parameters) {
            if (JsonSchemaGenerator.isInjected(parameter.getType())
                    || !JsonSchemaGenerator.isRequired(parameter)) {
                continue;
            }
            String required = JsonSchemaGenerator.resolveName(parameter,
                    parameter.getAnnotation(LlmToolParam.class));
            if (!present(arguments, required)) {
                return ToolResult.error("缺少必填参数 [" + required + "]，请补齐后重试");
            }
        }
        try {
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                if (ToolContext.class.isAssignableFrom(parameter.getType())) {
                    args[i] = context;
                    continue;
                }
                if (AgentSession.class.isAssignableFrom(parameter.getType())) {
                    args[i] = sessionOf(context, parameter);
                    continue;
                }
                String name = JsonSchemaGenerator.resolveName(parameter,
                        parameter.getAnnotation(LlmToolParam.class));
                Object raw = arguments == null ? null : arguments.get(name);
                args[i] = bind(raw, parameter);
            }
            Object returned = method.invoke(bean, args);
            return toResult(returned, arguments);
        } catch (InjectionException e) {
            return ToolResult.error(e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            log.debug("工具 {} 执行异常", spec.name(), cause);
            return ToolResult.error("工具执行抛出 " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("工具参数不合法: " + e.getMessage()
                    + "；收到的参数为 " + Json.writeQuietly(arguments));
        } catch (ReflectiveOperationException e) {
            return ToolResult.error("工具调用失败: " + e);
        }
    }

    /** 参数是否真的传了值：缺失、显式 null、JSON null 都算没传。 */
    private static boolean present(Map<String, Object> arguments, String name) {
        if (arguments == null || !arguments.containsKey(name)) {
            return false;
        }
        Object raw = arguments.get(name);
        return raw != null && !(raw instanceof JsonNode node && node.isNull());
    }

    /**
     * 取出要注入的会话句柄。
     *
     * <p>以前这里写死 {@code args[i] = null}：参数被排除出 schema（"框架注入"），
     * 工具里写 {@code session.id()} 却必然 NPE —— 典型的"看起来能用、静默为 null"。
     * 现在拿不到句柄就明确报错，让调用方在第一次执行时就看见原因。</p>
     */
    private AgentSession sessionOf(ToolContext context, Parameter parameter) {
        String where = "工具 [" + spec.name() + "] 的参数 [" + parameter.getName() + "]";
        if (context == null) {
            throw new InjectionException(where + " 声明了 AgentSession，"
                    + "但本次调用没有传入执行上下文（ToolContext 为 null）");
        }
        return context.session().orElseThrow(() -> new InjectionException(
                where + " 声明了 AgentSession，但执行上下文实现 "
                        + context.getClass().getSimpleName() + " 不提供会话句柄；"
                        + "请改用 ToolContext#sessionId() 读取会话 id"));
    }

    /** 框架注入参数无法填充。单独建类型是为了不被"参数不合法"那条兜底分支吞掉语义。 */
    public static class InjectionException extends IllegalStateException {
        public InjectionException(String message) {
            super(message);
        }
    }

    /** 把 JSON 值绑定到具体参数类型；类型不匹配时退化为宽松转换。 */
    private Object bind(Object raw, Parameter parameter) {
        Class<?> type = parameter.getType();
        LlmToolParam annotation = parameter.getAnnotation(LlmToolParam.class);
        if (raw == null && annotation != null && !annotation.defaultValue().isBlank()) {
            raw = annotation.defaultValue();
        }
        if (raw == null) {
            if (Optional.class.isAssignableFrom(type)) {
                // 缺参必须绑成 Optional.empty()，而不是 null ——
                // 否则"可选参数"会变成唯一一个按文档自然写法（note.orElse("兜底")）必然 NPE 的参数。
                return Optional.empty();
            }
            if (type.isPrimitive()) {
                return primitiveDefault(type);
            }
            return null;
        }
        if (type.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof JsonNode node) {
            if (node.isNull()) {
                return Optional.class.isAssignableFrom(type) ? Optional.empty() : null;
            }
            return Json.convert(node, type);
        }
        try {
            return Json.convert(raw, type);
        } catch (IllegalArgumentException e) {
            // 例如模型把数字给成了字符串
            if (type == String.class) {
                return String.valueOf(raw);
            }
            if (Number.class.isAssignableFrom(boxed(type))) {
                try {
                    return Json.convert(String.valueOf(raw), type);
                } catch (IllegalArgumentException ignored) {
                    throw e;
                }
            }
            if (type == boolean.class || type == Boolean.class) {
                return Boolean.parseBoolean(String.valueOf(raw));
            }
            throw e;
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return null;
    }

    private ToolResult toResult(Object returned, Map<String, Object> arguments) {
        if (returned == null) {
            return ToolResult.ok("(无返回值)");
        }
        if (returned instanceof ToolResult result) {
            return result;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", spec.name());
        if (returned instanceof CharSequence || returned instanceof Number || returned instanceof Boolean) {
            return ToolResult.ok(String.valueOf(returned), meta);
        }
        return ToolResult.ok(Json.writeQuietly(returned), meta);
    }

    @Override
    public String toString() {
        return "MethodToolCallback(" + spec.name() + " → " + method.toGenericString() + ")";
    }
}