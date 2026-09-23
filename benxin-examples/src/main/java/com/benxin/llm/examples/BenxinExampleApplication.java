package com.benxin.llm.examples;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本心示例应用。
 *
 * <p>默认使用内置的 {@code MockLlmModel}（离线可跑、不消耗任何 token），
 * 因此 {@code mvn spring-boot:run} 之后能立刻看到完整的 Agent 循环、工具调用与流式输出。
 * 想接真实模型时，激活 {@code real} profile 并填好 API Key 即可：
 * {@code mvn spring-boot:run -Dspring-boot.run.profiles=real}。</p>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.llm.examples")
public class BenxinExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenxinExampleApplication.class, args);
    }
}