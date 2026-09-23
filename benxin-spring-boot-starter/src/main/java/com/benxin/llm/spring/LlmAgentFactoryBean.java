package com.benxin.llm.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Proxy;

/**
 * 为 {@code @LlmAgent} 接口生成 JDK 动态代理并注册成容器里的 bean，
 * 因此使用者只需要 {@code @Autowired} 注入接口本身，完全不必接触 Agent API。
 */
public class LlmAgentFactoryBean implements FactoryBean<Object>, InitializingBean {

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
}