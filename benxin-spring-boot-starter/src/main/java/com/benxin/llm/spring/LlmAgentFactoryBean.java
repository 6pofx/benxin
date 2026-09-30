package com.benxin.llm.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartFactoryBean;

import java.lang.reflect.Proxy;

/**
 * 为 {@code @LlmAgent} 接口生成 JDK 动态代理并注册成容器里的 bean，
 * 因此使用者只需要 {@code @Autowired} 注入接口本身，完全不必接触 Agent API。
 *
 * <p><b>为什么实现了 {@link SmartFactoryBean} 且 {@code isEagerInit() == true}：</b>
 * {@code AgentRegistry} 的条目只在 {@link #getObject()} 里经 {@code registerLazy} 登记。
 * 普通 {@code FactoryBean} 在容器启动期只实例化自身、<b>不会</b>调用 {@code getObject()}，
 * 于是"只声明了 {@code @LlmAgent} 接口、没有别处注入它"的应用里注册表是空的：
 * {@code GET /llm/agents} 返回 {@code []}，{@code /chat} 直接 500 —— 而 bean 定义明明都在，
 * 排查时会被"注解没生效"这个假象带偏。声明成 eager-init 之后，
 * 启动期就会把每个 Agent 登记进注册表，冷启动即可见。</p>
 */
public class LlmAgentFactoryBean implements FactoryBean<Object>, SmartFactoryBean<Object>, InitializingBean {

    private final Class<?> agentInterface;
    private final String agentName;
    private final BeanFactory beanFactory;

    private volatile Object proxy;

    public LlmAgentFactoryBean(Class<?> agentInterface, String agentName, BeanFactory beanFactory) {
        this.agentInterface = agentInterface;
        this.agentName = agentName;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        if (!agentInterface.isInterface()) {
            throw new LlmAgentInvocationException(
                    "@LlmAgent 只能标注在接口上，而 " + agentInterface.getName() + " 是一个类");
        }
    }

    @Override
    public Object getObject() {
        Object current = proxy;
        if (current == null) {
            synchronized (this) {
                current = proxy;
                if (current == null) {
                    // 延迟到首次取用时才解析 LlmAgentFactory：它是普通 bean，
                    // 若在 BeanDefinition 注册阶段就强引用会造成过早初始化。
                    LlmAgentFactory factory = beanFactory.getBean(LlmAgentFactory.class);
                    AgentRegistry registry = beanFactory.getBean(AgentRegistry.class);
                    LlmAgentInvocationHandler handler =
                            new LlmAgentInvocationHandler(agentInterface, agentName, factory, registry);
                    // 立刻按名字登记一个惰性条目，而不是等第一次调用才登记：
                    // 否则"从未被调用过"的 Agent 既不会出现在 /llm/agents 列表里，
                    // 也无法被子代理按名解析到。
                    registry.registerLazy(agentName, handler::agent);
                    current = Proxy.newProxyInstance(
                            agentInterface.getClassLoader(),
                            new Class<?>[]{agentInterface},
                            handler);
                    proxy = current;
                }
            }
        }
        return current;
    }

    @Override
    public Class<?> getObjectType() {
        return agentInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * 启动期就调用 {@link #getObject()}，让每个 Agent 立即登记进 {@link AgentRegistry}。
     *
     * <p>代价是启动时会真的把 Agent 装配出来（这也是 {@code llm.workflows.*} 那类配置错误
     * 提前暴露的机会）；收益是"冷启动即可用" —— 端点、子代理按名解析都不再依赖某个别处
     * 恰好注入过这些接口。</p>
     */
    @Override
    public boolean isEagerInit() {
        return true;
    }
}