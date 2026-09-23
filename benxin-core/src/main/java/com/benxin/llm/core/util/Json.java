package com.benxin.llm.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** 全局共享的 Jackson 门面。所有协议编解码都走这里，便于用户统一替换序列化行为。 */
public final class Json {

    private static volatile ObjectMapper mapper = createDefault();

    private Json() {
    }

    private static ObjectMapper createDefault() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return m;
    }

    public static ObjectMapper mapper() {
        return mapper;
    }

    /** 允许宿主应用替换全局 ObjectMapper（例如接入 Spring 容器里的那个）。 */
    public static void setMapper(ObjectMapper replacement) {
        if (replacement != null) {
            mapper = replacement;
        }
    }

    public static ObjectNode object() {
        return mapper.createObjectNode();
    }

    public static ArrayNode array() {
        return mapper.createArrayNode();
    }

    public static JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("非法 JSON: " + abbreviate(text), e);
        }
    }

    public static JsonNode parseQuietly(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 序列化一个对象为 JSON 文本；失败时退化为 {@code String.valueOf}，绝不抛异常。 */
    public static String writeQuietly(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, Map.class);
    }

    public static JsonNode valueToTree(Object value) {
        return value == null ? null : mapper.valueToTree(value);
    }

    public static <T> T convert(Object from, Class<T> type) {
        return mapper.convertValue(from, type);
    }

    public static <T> List<T> convertList(Object from, Class<T> type) {
        return mapper.convertValue(from, mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    public static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...(截断)";
    }
}