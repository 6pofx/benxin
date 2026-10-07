package com.benxin.llm.core.tool;

import com.benxin.llm.core.annotation.LlmRetry;
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
            ToolCallback callback = new MethodToolCallback(bean, method);
            LlmRetry retry = method.getAnnotation(LlmRetry.class);
            if (retry == null) {
                retry = type.getAnnotation(LlmRetry.class);
            }
            if (retry != null) {
                // 方法上的 @LlmRetry 以前没有任何消费点（打了等于没打）。这里把它落到工具执行层。
                callback = new RetryingToolCallback(callback, retry.maxAttempts(),
                        retry.backoffMillis(), retry.multiplier());
                log.debug("工具 [{}] 启用重试：最多 {} 次，首次退避 {}ms，倍数 {}",
                        callback.name(), retry.maxAttempts(), retry.backoffMillis(), retry.multiplier());
            }
            callbacks.add(callback);
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