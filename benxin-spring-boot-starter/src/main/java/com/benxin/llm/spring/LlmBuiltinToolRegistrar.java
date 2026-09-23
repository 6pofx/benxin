package com.benxin.llm.spring;

import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.builtin.BuiltinToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 把内置文件/命令工具注册进工具目录。
 *
 * <p>只在 {@code llm.tools.builtin-enabled=true} 时才装配，且写文件、执行命令还要各自
 * 再显式打开。三重开关是刻意的：这些工具能真实改动宿主机，误开的代价远大于多写两行配置。</p>
 */
public class LlmBuiltinToolRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LlmBuiltinToolRegistrar.class);

    private final LlmProperties properties;
    private final LlmToolCatalog catalog;

    public LlmBuiltinToolRegistrar(LlmProperties properties, LlmToolCatalog catalog) {
        this.properties = properties;
        this.catalog = catalog;
    }

    @Override
    public void afterPropertiesSet() {
        LlmProperties.ToolProperties tools = properties.getTools();
        BuiltinToolkit.Builder builder = BuiltinToolkit.builder().config(toSandboxConfig(tools));
        // llm.tools.enabled 是显式清单：给了就按它来（叠加在只读基线上）；
        // 没给才依据 allow-write / allow-exec 推导 —— 于是"授权即启用"与"精确点名"两种用法都成立。
        if (!tools.getEnabled().isEmpty()) {
            builder.enable(tools.getEnabled());
        }
        List<ToolCallback> callbacks = builder.build();
        callbacks.forEach(catalog::register);
        log.warn("[benxin] 已启用内置工具 {}（写文件={}，执行命令={}）—— 请确认沙箱根目录与审批策略符合预期：{}",
                callbacks.stream().map(ToolCallback::name).toList(),
                tools.isAllowWrite(), tools.isAllowExec(), resolveWorkdir(tools));
    }

    static Path resolveWorkdir(LlmProperties.ToolProperties tools) {
        return tools.getWorkdir() == null || tools.getWorkdir().isBlank()
                ? Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : Paths.get(tools.getWorkdir()).toAbsolutePath().normalize();
    }

    /** 配置 → 沙箱配置。集中在一处转换，便于将来增删字段时只改一个地方。 */
    public static SandboxConfig toSandboxConfig(LlmProperties.ToolProperties tools) {
        SandboxConfig.Builder builder = SandboxConfig.builder()
                .workdir(resolveWorkdir(tools))
                .allowWrite(tools.isAllowWrite())
                .allowExec(tools.isAllowExec())
                .execTimeout(tools.getExecTimeout())
                .maxOutputChars(tools.getMaxOutputChars())
                .maxFileBytes(tools.getMaxFileBytes());
        if (!tools.getAllowedCommands().isEmpty()) {
            builder.allowedCommands(tools.getAllowedCommands());
        }
        if (!tools.getDeniedCommands().isEmpty()) {
            builder.deniedCommands(tools.getDeniedCommands());
        }
        return builder.build();
    }
}