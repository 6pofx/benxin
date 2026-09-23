package com.benxin.llm.core.tool;

import com.benxin.llm.core.annotation.LlmTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code @LlmTool} 扫描器：把一个普通对象上标注了注解的方法收集成工具。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>方法级注解 —— 只收集标注了 {@code @LlmTool} 的方法；</li>
 *   <li>类级注解 —— 类上标了 {@code @LlmTool}，则所有 public 方法都是工具。</li>
 * </ul>
 */
public final class ToolScanner {

    private static final Logger log = LoggerFactory.getLogger(ToolScanner.class);

    private ToolScanner() {
    }

    /** 扫描一个对象，返回其暴露的工具。 */
    public static List<ToolCallback> scan(Object bean) {
        if (bean == null) {
            return List.of();
        }
        return scan(bean, bean.getClass());
    }

    public static List<ToolCallback> scan(Object bean, Class<?> type) {
        if (bean == null || type == null) {
            return List.of();
        }
        boolean classLevel = type.isAnnotationPresent(LlmTool.class);
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            LlmTool annotation = method.getAnnotation(LlmTool.class);
            boolean exposed = annotation != null ? annotation.enabled() : classLevel;
            if (!exposed) {
                continue;
            }
            if (method.getReturnType() == void.class) {
                log.debug("跳过无返回值的工具方法 {}", method);
                continue;
            }
            callbacks.add(new MethodToolCallback(bean, method));
        }
        return callbacks;
    }

    /** 扫描一组对象的全部工具。 */
    public static List<ToolCallback> scanAll(Object... beans) {
        List<ToolCallback> all = new ArrayList<>();
        if (beans != null) {
            for (Object bean : beans) {
                all.addAll(scan(bean));
            }
        }
        return all;
    }

    public static boolean hasTools(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type.isAnnotationPresent(LlmTool.class)) {
            return true;
        }
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(LlmTool.class)) {
                return true;
            }
        }
        return false;
    }
}