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

    public MethodToolCallback(Object bean, Method method) {
        this.bean = bean;
        this.method = method;
        this.method.setAccessible(true);
        this.spec = buildSpec(method);
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
        return false;
    }

    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        try {
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                if (ToolContext.class.isAssignableFrom(parameter.getType())) {
                    args[i] = context;
                    continue;
                }
                if (AgentSession.class.isAssignableFrom(parameter.getType())) {
                    args[i] = null; // 需要会话时改用 ToolContext#sessionId()
                    continue;
                }
                String name = JsonSchemaGenerator.resolveName(parameter,
                        parameter.getAnnotation(LlmToolParam.class));
                Object raw = arguments == null ? null : arguments.get(name);
                args[i] = bind(raw, parameter);
            }
            Object returned = method.invoke(bean, args);
            return toResult(returned, arguments);
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

    /** 把 JSON 值绑定到具体参数类型；类型不匹配时退化为宽松转换。 */
    private Object bind(Object raw, Parameter parameter) {
        Class<?> type = parameter.getType();
        LlmToolParam annotation = parameter.getAnnotation(LlmToolParam.class);
        if (raw == null && annotation != null && !annotation.defaultValue().isBlank()) {
            raw = annotation.defaultValue();
        }
        if (raw == null) {
            if (type.isPrimitive()) {
                return primitiveDefault(type);
            }
            return null;
        }
        if (type.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof JsonNode node) {
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