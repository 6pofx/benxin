package com.benxin.llm.core.workflow;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * SnakeYAML 的反射外壳。
 *
 * <p>本心的 core 只有 Jackson 一个硬依赖，而 YAML 只是"声明式工作流"的一个可选入口。
 * 为了不让整个 core 为了 YAML 背上一个必装依赖，这里用反射访问 SnakeYAML：
 * 类路径上有就用，没有就报一句能直接照做的话。</p>
 *
 * <p>这个取舍与 {@code protocol/Codecs} 里用类名字符串注册编解码器是同一个思路 ——
 * <b>把"可选的实现"从编译期依赖降级成运行期发现</b>。对使用者而言，实际差异很小：
 * Spring Boot 应用天然带着 SnakeYAML（{@code spring-boot-starter} 的传递依赖），
 * 因此 YAML 开箱可用。</p>
 */
final class YamlSupport {

    private static final String YAML_CLASS = "org.yaml.snakeyaml.Yaml";

    private static final Constructor<?> CONSTRUCTOR;
    private static final Method LOAD;
    private static final Method DUMP;
    private static final String UNAVAILABLE_REASON;

    static {
        Constructor<?> constructor = null;
        Method load = null;
        Method dump = null;
        String reason = null;
        try {
            Class<?> type = Class.forName(YAML_CLASS);
            constructor = type.getDeclaredConstructor();
            load = type.getMethod("load", String.class);
            dump = type.getMethod("dump", Object.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            reason = "类路径上没有 " + YAML_CLASS;
        } catch (LinkageError e) {
            reason = "SnakeYAML 加载失败：" + e;
        }
        CONSTRUCTOR = constructor;
        LOAD = load;
        DUMP = dump;
        UNAVAILABLE_REASON = reason;
    }

    private YamlSupport() {
    }

    static boolean available() {
        return CONSTRUCTOR != null;
    }

    /** 供 {@link WorkflowIo} 在不可用时拼出可执行的提示。 */
    static String unavailableReason() {
        return UNAVAILABLE_REASON == null ? "未知原因" : UNAVAILABLE_REASON;
    }

    /** 解析 YAML 文本为普通 Map / List 结构。 */
    static Object load(String text) {
        requireAvailable();
        try {
            Object yaml = CONSTRUCTOR.newInstance();
            return LOAD.invoke(yaml, text);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("SnakeYAML 实例化失败", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalArgumentException("YAML 语法错误：" + cause.getMessage(), cause);
        }
    }

    /** 把普通 Map / List 结构写成 YAML 文本。 */
    static String dump(Object value) {
        requireAvailable();
        try {
            Object yaml = CONSTRUCTOR.newInstance();
            return String.valueOf(DUMP.invoke(yaml, value));
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("SnakeYAML 实例化失败", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("YAML 序列化失败：" + cause.getMessage(), cause);
        }
    }

    private static void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException("YAML 入口不可用（" + UNAVAILABLE_REASON
                    + "）。可选方案：改用 JSON 格式的工作流定义，或引入依赖 org.yaml:snakeyaml。");
        }
    }
}
