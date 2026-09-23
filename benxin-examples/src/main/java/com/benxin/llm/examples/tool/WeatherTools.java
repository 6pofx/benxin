package com.benxin.llm.examples.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 示例工具集：演示"把一个写好的方法打上 {@code @LlmTool} 就交给模型调用"。
 *
 * <p>注意返回类型：可以是 {@code String}、可以是 DTO（会被自动序列化成 JSON 文本），
 * 参数可以是基本类型、枚举、集合甚至自定义 POJO（参数会被自动绑回 Java 类型）。</p>
 */
@Component
public class WeatherTools {

    private static final Map<String, String> CONDITIONS = Map.of(
            "北京", "晴",
            "上海", "多云",
            "广州", "雷阵雨",
            "深圳", "阴",
            "杭州", "小雨");

    @LlmTool(name = "get_weather", description = "查询指定城市的当前天气与气温")
    public ObjectNode getWeather(
            @LlmToolParam(value = "city", description = "城市名称，例如：北京") String city,
            @LlmToolParam(value = "unit", description = "温度单位：celsius 或 fahrenheit",
                    required = false, defaultValue = "celsius") String unit) {

        int celsius = ThreadLocalRandom.current().nextInt(12, 33);
        int value = "fahrenheit".equalsIgnoreCase(unit) ? celsius * 9 / 5 + 32 : celsius;
        ObjectNode node = Json.object();
        node.put("city", city);
        node.put("condition", CONDITIONS.getOrDefault(city, "未知"));
        node.put("temperature", value);
        node.put("unit", unit == null ? "celsius" : unit);
        node.put("observedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return node;
    }

    @LlmTool(name = "get_current_time", description = "获取服务器当前时间")
    public String currentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @LlmTool(name = "calculate", description = "计算一个简单的四则运算表达式，例如 (12+5)*3")
    public String calculate(@LlmToolParam(value = "expression", description = "只含数字与 + - * / ( ) 的表达式")
                            String expression) {
        if (expression == null || !expression.matches("[0-9+\\-*/(). ]+")) {
            return "错误：表达式只允许出现数字与 + - * / ( ) 符号";
        }
        try {
            double value = new ExpressionEvaluator(expression).parse();
            return expression + " = " + value;
        } catch (RuntimeException e) {
            return "计算失败：" + e.getMessage();
        }
    }

    /** 一个不依赖任何第三方库的极简四则运算求值器，仅用于示例。 */
    private static final class ExpressionEvaluator {
        private final String text;
        private int pos = -1;
        private int ch;

        ExpressionEvaluator(String text) {
            this.text = text;
        }

        double parse() {
            nextChar();
            double value = parseExpression();
            if (pos < text.length()) {
                throw new IllegalArgumentException("存在无法解析的字符: " + (char) ch);
            }
            return value;
        }

        private void nextChar() {
            ch = ++pos < text.length() ? text.charAt(pos) : -1;
        }

        private boolean eat(int expected) {
            while (ch == ' ') {
                nextChar();
            }
            if (ch == expected) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (eat('+')) {
                    value += parseTerm();
                } else if (eat('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                if (eat('*')) {
                    value *= parseFactor();
                } else if (eat('/')) {
                    value /= parseFactor();
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            double value;
            int start = pos;
            if (eat('(')) {
                value = parseExpression();
                eat(')');
            } else {
                while (ch >= '0' && ch <= '9' || ch == '.') {
                    nextChar();
                }
                value = Double.parseDouble(text.substring(start, pos));
            }
            return value;
        }
    }
}