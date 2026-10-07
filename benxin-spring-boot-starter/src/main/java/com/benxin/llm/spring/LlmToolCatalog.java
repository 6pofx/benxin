package com.benxin.llm.spring;

import com.benxin.llm.core.tool.DefaultToolRegistry;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具目录：扫描容器里所有带 {@code @LlmTool} 方法的 bean，建成一张"工具名 → 工具"的总表，
 * 并额外记住每个工具来自哪个类，以便 {@code @LlmAgent(tools = {X.class})} 精确取用。
 *
 * <p>本类同时就是 {@link BeanPostProcessor}，因此必须在配置类里用
 * <b>static</b> 的 {@code @Bean} 方法注册，Spring 才能在普通 bean 实例化之前把它准备好，
 * 否则会出现"先创建的 bean 扫不到工具"的时序问题。</p>
 */
public class LlmToolCatalog implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmToolCatalog.class);

    private final ToolRegistry global = new DefaultToolRegistry();
    private final Map<Class<?>, List<ToolCallback>> bySourceClass = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<ToolCallback>> byDeclaredType = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            collect(beanName, bean);
        } catch (RuntimeException e) {
            // 工具扫描失败不应该阻止应用启动
            log.warn("[benxin] 扫描 bean [{}] 的工具时出错: {}", beanName, e.toString());
        }
        return bean;
    }

    private void collect(String beanName, Object bean) {
        if (bean == null || isInfrastructure(bean)) {
            return;
        }
        // 手写的 ToolCallback bean 直接登记 —— "实现接口"与"打注解"是等价的两种扩展方式，
        // 没有理由要求用户为了走 bean 路径再套一层注解。
        if (bean instanceof ToolCallback callback) {
            global.register(callback);
            bySourceClass.computeIfAbsent(AopUtils.getTargetClass(bean), k -> new ArrayList<>()).add(callback);
            log.info("[benxin] 注册手工工具 [{}]（来自 bean [{}]）", callback.name(), beanName);
            return;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!ToolScanner.hasTools(targetClass)) {
            return;
        }
        // 工具方法的反射调用需要真实目标对象：JDK 动态代理上调用目标类的方法会失败，
        // 因此这里尽量取出被代理的原始实例（CGLIB 代理本身可直接调用，保留代理还能享受其增强）。
        Object invocationTarget = bean;
        if (AopUtils.isJdkDynamicProxy(bean)) {
            Object target = AopProxyUtils.getSingletonTarget(bean);
            if (target != null) {
                invocationTarget = target;
            } else {
                log.warn("[benxin] bean [{}] 是 JDK 动态代理且取不到目标实例，其工具方法可能调用失败", beanName);
            }
        }
        List<ToolCallback> tools = ToolScanner.scan(invocationTarget, targetClass);
        if (tools.isEmpty()) {
            return;
        }
        tools.forEach(global::register);
        List<ToolCallback> snapshot = List.copyOf(tools);
        bySourceClass.put(targetClass, snapshot);
        for (Class<?> declared : org.springframework.util.ClassUtils.getAllInterfacesForClassAsSet(bean.getClass())) {
            if (ToolScanner.hasTools(declared)) {
                byDeclaredType.put(declared, snapshot);
            }
        }
        log.info("[benxin] 从 bean [{}] 注册了 {} 个工具: {}", beanName, tools.size(),
                tools.stream().map(ToolCallback::name).toList());
    }

    /** 跳过 Spring 自身的基础设施 bean，避免无谓反射。 */
    private boolean isInfrastructure(Object bean) {
        String name = bean.getClass().getName();
        return name.startsWith("org.springframework.") || name.startsWith("org.apache.");
    }

    /** 全局工具表。 */
    public ToolRegistry global() {
        return global;
    }

    /** 取指定类（含其实现的接口、以及它作为外层类的内部类）上声明的工具。 */
    public ToolRegistry forClasses(Class<?>[] classes) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        if (classes == null) {
            return registry;
        }
        for (Class<?> type : classes) {
            List<ToolCallback> tools = bySourceClass.get(type);
            if (tools == null) {
                tools = byDeclaredType.get(type);
            }
            if (tools == null) {
                tools = nestedInside(type);
                if (tools != null) {
                    log.info("[benxin] @LlmAgent(tools = {}.class) 经外层类回退匹配到 {} 个工具（来源是它的内部类）",
                            type.getSimpleName(), tools.size());
                }
            }
            if (tools == null) {
                log.warn("[benxin] @LlmAgent(tools = {}.class) 未匹配到任何工具，"
                                + "请确认该类已交给 Spring 管理且方法上标注了 @LlmTool；当前已知的工具来源类有 {}",
                        type.getName(), snapshot().keySet().stream().map(Class::getName).toList());
                continue;
            }
            tools.forEach(registry::register);
        }
        return registry;
    }

    /**
     * 回退匹配：把"工具 bean 是所请求类的内部类"也算命中。
     *
     * <p>写类工具很常见的写法是 {@code @Bean ToolCallback}（返回外层类的内部类实例），
     * 此时登记的来源类是 {@code OrderWriteTools$CreateOrderTool}，而
     * {@code @LlmAgent(tools = OrderWriteTools.class)} 写的是外层类。
     * 以前这里只做精确匹配，于是"一个工具都装不上、只留一行 WARN"。</p>
     */
    private List<ToolCallback> nestedInside(Class<?> outer) {
        List<ToolCallback> matched = new ArrayList<>();
        for (Map.Entry<Class<?>, List<ToolCallback>> entry : bySourceClass.entrySet()) {
            if (isNestedIn(entry.getKey(), outer)) {
                matched.addAll(entry.getValue());
            }
        }
        for (Map.Entry<Class<?>, List<ToolCallback>> entry : byDeclaredType.entrySet()) {
            if (!bySourceClass.containsKey(entry.getKey()) && isNestedIn(entry.getKey(), outer)) {
                matched.addAll(entry.getValue());
            }
        }
        return matched.isEmpty() ? null : List.copyOf(matched);
    }

    private static boolean isNestedIn(Class<?> candidate, Class<?> outer) {
        for (Class<?> current = candidate; current != null; current = current.getEnclosingClass()) {
            if (current.equals(outer)) {
                return true;
            }
        }
        return false;
    }

    /** 按工具名取工具；名字不存在时记录警告而不是抛异常。 */
    public ToolRegistry forNames(String[] names, String agentName) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        if (names == null) {
            return registry;
        }
        for (String name : names) {
            global.find(name).ifPresentOrElse(registry::register,
                    () -> log.warn("[benxin] Agent [{}] 引用了不存在的工具 [{}]", agentName, name));
        }
        return registry;
    }

    /** 所有已知工具来源类的快照，便于排查"我的工具为什么没被注册"。 */
    public Map<Class<?>, List<ToolCallback>> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bySourceClass));
    }

    /** 手动登记工具（例如从 MCP 动态拉取或在 @Bean 里手工构造的）。 */
    public void register(ToolCallback callback) {
        register(callback, callback.getClass());
    }

    /**
     * 手动登记工具并显式指定"来源类"，让 {@code @LlmAgent(tools = X.class)} 能精确取到它。
     *
     * <p>动态生成的工具（MCP 桥接、匿名类、lambda）没有稳定的类名可依赖，
     * 由调用方直接说明来源比让插件去猜更可靠。</p>
     *
     * @param sourceClass 来源类；为 {@code null} 时退回 {@code callback.getClass()}
     */
    public void register(ToolCallback callback, Class<?> sourceClass) {
        global.register(callback);
        Class<?> key = sourceClass == null ? callback.getClass() : sourceClass;
        bySourceClass.computeIfAbsent(key, k -> new ArrayList<>()).add(callback);
    }
}