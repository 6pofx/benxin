package com.benxin.llm.core.tool;

import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 Java 反射信息翻译成 JSON Schema，供 {@code @LlmTool} 方法自动生成工具声明。
 *
 * <p>覆盖三种协议共通的 JSON Schema 子集：{@code object / array / string / number /
 * integer / boolean / enum}。POJO 会递归展开，深度超过 {@value #MAX_DEPTH} 时退化成自由对象，
 * 避免自引用类型把 schema 撑爆。</p>
 */
public final class JsonSchemaGenerator {

    private static final int MAX_DEPTH = 4;

    private JsonSchemaGenerator() {
    }

    /** 为一个工具方法生成参数 schema（永远是 object 类型）。 */
    public static ObjectNode forMethod(Method method) {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = Json.array();

        Parameter[] parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            if (isInjected(parameter.getType())) {
                continue; // ToolContext / AgentSession 一类由框架注入，不暴露给模型
            }
            LlmToolParam annotation = parameter.getAnnotation(LlmToolParam.class);
            String name = resolveName(parameter, annotation);
            ObjectNode propertySchema = forType(parameter.getParameterizedType(), 0);
            if (annotation != null && !annotation.description().isBlank()) {
                propertySchema.put("description", annotation.description());
            }
            if (annotation != null && !annotation.defaultValue().isBlank()) {
                propertySchema.put("default", annotation.defaultValue());
            }
            properties.set(name, propertySchema);
            boolean isRequired = annotation == null || annotation.required();
            if (isRequired && (annotation == null || annotation.defaultValue().isBlank())) {
                required.add(name);
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 判断某个参数类型是否由框架注入而非模型提供。 */
    public static boolean isInjected(Class<?> type) {
        return ToolContext.class.isAssignableFrom(type)
                || com.benxin.llm.core.agent.AgentSession.class.isAssignableFrom(type);
    }

    /** 解析参数名：注解优先，其次编译期保留的真实参数名（需要 {@code -parameters}），最后 argN。 */
    public static String resolveName(Parameter parameter, LlmToolParam annotation) {
        if (annotation != null) {
            if (!annotation.value().isBlank()) {
                return annotation.value();
            }
            if (!annotation.name().isBlank()) {
                return annotation.name();
            }
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        return "arg" + indexOf(parameter);
    }

    private static int indexOf(Parameter parameter) {
        Parameter[] all = parameter.getDeclaringExecutable().getParameters();
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(parameter)) {
                return i;
            }
        }
        return 0;
    }

    /** 为一个 Java 类型生成 schema。 */
    public static ObjectNode forType(Type type, int depth) {
        ObjectNode node = Json.object();
        if (depth > MAX_DEPTH) {
            node.put("type", "object");
            return node;
        }
        Class<?> raw = rawClass(type);
        if (raw == null) {
            return node; // 泛型变量等未知类型 -> 任意类型
        }

        if (raw == String.class || CharSequence.class.isAssignableFrom(raw) || Character.class == raw
                || char.class == raw || java.util.UUID.class == raw) {
            node.put("type", "string");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class) {
            node.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class) {
            node.put("type", "number");
        } else if (raw == boolean.class || raw == Boolean.class) {
            node.put("type", "boolean");
        } else if (raw.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
        } else if (Optional.class.isAssignableFrom(raw)) {
            Type inner = typeArgument(type, 0);
            return inner == null ? node : forType(inner, depth + 1);
        } else if (raw.isArray()) {
            node.put("type", "array");
            node.set("items", forType(raw.getComponentType(), depth + 1));
        } else if (Collection.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)) {
            node.put("type", "array");
            Type inner = typeArgument(type, 0);
            node.set("items", inner == null ? Json.object() : forType(inner, depth + 1));
        } else if (Map.class.isAssignableFrom(raw)) {
            node.put("type", "object");
        } else if (JsonNode.class.isAssignableFrom(raw) || Object.class == raw) {
            // 任意结构，不加约束
        } else if (raw.getName().startsWith("java.") || raw.getName().startsWith("javax.")) {
            node.put("type", "string");
        } else {
            node.put("type", "object");
            ObjectNode properties = node.putObject("properties");
            ArrayNode required = Json.array();
            for (Field field : fieldsOf(raw)) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                ObjectNode fieldSchema = forType(field.getGenericType(), depth + 1);
                properties.set(field.getName(), fieldSchema);
                required.add(field.getName());
            }
            if (!required.isEmpty()) {
                node.set("required", required);
            }
            node.put("additionalProperties", false);
        }
        return node;
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (seen.add(field.getName())) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return rawClass(parameterized.getRawType());
        }
        if (type instanceof java.lang.reflect.GenericArrayType arrayType) {
            return java.lang.reflect.Array.newInstance(rawClass(arrayType.getGenericComponentType()), 0).getClass();
        }
        return null;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (index < arguments.length) {
                return arguments[index];
            }
        }
        return null;
    }
}