package com.benxin.llm.core.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 注解 → JSON Schema：模型靠它决定"这个工具怎么调"。 */
class JsonSchemaGeneratorTest {

    enum Color { RED, GREEN }

    record Address(String city, String street) {
    }

    record Person(String name, int age, Address address) {
    }

    @SuppressWarnings("unused")
    static class Toolbox {

        @LlmTool(name = "typed", description = "覆盖各种参数类型")
        public String typed(
                @LlmToolParam(value = "text", description = "字符串") String text,
                @LlmToolParam(value = "count", description = "整数") int count,
                @LlmToolParam(value = "ratio", description = "小数") double ratio,
                @LlmToolParam(value = "flag", description = "布尔") boolean flag,
                @LlmToolParam(value = "color", description = "枚举") Color color,
                @LlmToolParam(value = "tags", description = "字符串列表") List<String> tags,
                @LlmToolParam(value = "person", description = "嵌套对象") Person person,
                @LlmToolParam(value = "maybe", description = "可选值", required = false) Optional<String> maybe,
                @LlmToolParam(value = "withDefault", description = "带默认值", required = false,
                        defaultValue = "auto") String withDefault) {
            return "";
        }
    }

    private JsonNode schemaOf(String methodName) {
        try {
            return JsonSchemaGenerator.forMethod(
                    Toolbox.class.getMethod(methodName, String.class, int.class, double.class, boolean.class,
                            Color.class, List.class, Person.class, Optional.class, String.class));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("根节点永远是 object，且带 properties")
    void rootIsObject() {
        JsonNode schema = schemaOf("typed");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.has("properties")).isTrue();
    }

    @Test
    @DisplayName("Java 类型到 JSON Schema 类型的映射")
    void mapsPrimitiveTypes() {
        JsonNode properties = schemaOf("typed").get("properties");

        assertThat(properties.get("text").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("count").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("ratio").get("type").asText()).isEqualTo("number");
        assertThat(properties.get("flag").get("type").asText()).isEqualTo("boolean");
    }

    @Test
    @DisplayName("枚举展开成 string + enum 取值列表")
    void mapsEnum() {
        JsonNode color = schemaOf("typed").get("properties").get("color");
        assertThat(color.get("type").asText()).isEqualTo("string");
        assertThat(color.get("enum")).hasSize(2);
        assertThat(color.get("enum").get(0).asText()).isEqualTo("RED");
    }

    @Test
    @DisplayName("集合映射成 array 并带 items")
    void mapsCollection() {
        JsonNode tags = schemaOf("typed").get("properties").get("tags");
        assertThat(tags.get("type").asText()).isEqualTo("array");
        assertThat(tags.get("items").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("POJO 递归展开，Optional 解包成内层类型")
    void mapsNestedPojoAndOptional() {
        JsonNode properties = schemaOf("typed").get("properties");

        JsonNode person = properties.get("person");
        assertThat(person.get("type").asText()).isEqualTo("object");
        assertThat(person.get("properties").has("name")).isTrue();
        assertThat(person.get("properties").get("address").get("type").asText()).isEqualTo("object");

        assertThat(properties.get("maybe").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("描述被写入 schema，required 反映注解与默认值")
    void writesDescriptionAndRequired() {
        JsonNode schema = schemaOf("typed");
        JsonNode properties = schema.get("properties");
        List<String> required = new java.util.ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asText()));

        assertThat(properties.get("text").get("description").asText()).isEqualTo("字符串");
        assertThat(required).contains("text", "count", "person");
        // required = false 的字段不应出现在 required 里
        assertThat(required).doesNotContain("maybe");
        // 带默认值的字段即使 required 默认 true 也不应强制
        assertThat(required).doesNotContain("withDefault");
        assertThat(properties.get("withDefault").get("default").asText()).isEqualTo("auto");
    }
}