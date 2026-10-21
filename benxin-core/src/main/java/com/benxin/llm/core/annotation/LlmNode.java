package com.benxin.llm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式工作流节点处理器：标记在一个 {@code WorkflowHandler} 实现类上，
 * 该 bean 就会被自动登记进工作流处理器注册表，从而可以被工作流定义按名字引用。
 *
 * <pre>{@code
 * @Component
 * @LlmNode("checksum")
 * public class ChecksumHandler implements WorkflowHandler {
 *     public NodeOutput handle(NodeInput in) { ... }
 * }
 * }</pre>
 *
 * <p>对应的 YAML：</p>
 * <pre>{@code
 * - id: verify
 *   type: java
 *   handler: checksum
 *   args:
 *     text: "${analyze}"
 * }</pre>
 *
 * <p><b>注解上的名字就是唯一的对外契约</b>：工作流定义只认这个名字，看不到类名，
 * 因此重命名/重构实现类不会悄悄打断已经在运行的工作流。没有打这个注解的
 * {@code WorkflowHandler} bean 会让启动失败，而不是被静默忽略 ——
 * 一份"注册了但用不上"的代码，排查成本远高于一条启动期报错。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmNode {

    /** 处理器名字，工作流定义里的 {@code handler:} 引用它。 */
    String value();
}
