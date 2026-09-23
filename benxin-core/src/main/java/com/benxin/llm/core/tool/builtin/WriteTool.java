package com.benxin.llm.core.tool.builtin;

import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入文件工具（{@code write}）：整体覆盖写，自动创建父目录。
 *
 * <p>这是本插件里"最危险"的两个工具之一，因此：</p>
 * <ul>
 *   <li>{@link #requiresApproval()} 返回 {@code true}——写操作必须有人点头；</li>
 *   <li>{@link #parallelSafe()} 返回 {@code false}——有副作用，并行执行会让结果不可预测；</li>
 *   <li>路径必须先过 {@link PathSandbox#resolve}，父目录越界一律拒绝，
 *       不会"顺手"在围栏外面建目录。</li>
 * </ul>
 */
public class WriteTool implements ToolCallback {

    public static final String TOOL_NAME = "write";

    private final SandboxConfig config;
    private final ToolSpec spec;

    public WriteTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("WriteTool 需要非空的 SandboxConfig");
        }
        this.config = config;
        this.spec = buildSpec();
    }

    private static ToolSpec buildSpec() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "要写入的文件路径（相对沙箱工作目录），父目录不存在会自动创建");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "文件的完整内容；会整体覆盖原文件，请先 read 再改");

        schema.putArray("required").add("path").add("content");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "把 content 整体写入 path（覆盖已有内容），自动创建父目录。"
                        + "修改已有文件请优先用 edit，避免整文件重写带来的误伤。",
                schema);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String rawPath = asString(args.get("path"));
            if (rawPath == null || rawPath.isBlank()) {
                return ToolResult.error("参数 path 必填：请给出要写入的文件路径。");
            }
            if (!args.containsKey("content")) {
                return ToolResult.error("参数 content 必填：请给出文件的完整内容（可以是空字符串）。");
            }
            String content = args.get("content") == null ? "" : String.valueOf(args.get("content"));

            if (!config.allowWrite()) {
                // 纵深防御：即使沙箱被替换成宽松实现，写工具自己也不会在只读配置下落地。
                return ToolResult.error("当前沙箱 allowWrite=false（默认只读），拒绝写入 " + rawPath + "。"
                        + "开启方式：SandboxConfig.builder().allowWrite(true).build()。");
            }

            PathSandbox.Resolution resolution = PathSandbox.resolve(config, rawPath);
            if (!resolution.allowed()) {
                return ToolResult.error(resolution.rejectReason());
            }
            Path path = resolution.path();
            if (Files.isDirectory(path)) {
                return ToolResult.error("[" + rawPath + "] 是一个已存在的目录，不能当文件写入。");
            }

            Path parent = path.getParent();
            if (parent != null && !PathSandbox.isInside(config, parent)) {
                // 父目录同样要过围栏：用 write 在围栏外"顺手"建目录是不允许的。
                return ToolResult.error("父目录越界，拒绝创建：" + parent + " 不在沙箱围栏 "
                        + config.fence() + " 之内。请改用围栏内的相对路径。");
            }
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            long previousSize = Files.exists(path) ? Files.size(path) : -1L;
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            int lines = content.isEmpty() ? 0 : (int) content.lines().count();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("path", BuiltinToolkit.relative(config, path));
            meta.put("bytes", bytes.length);
            meta.put("lines", lines);
            meta.put("overwritten", previousSize >= 0);
            if (previousSize >= 0) {
                meta.put("previousBytes", previousSize);
            }

            StringBuilder message = new StringBuilder();
            message.append(previousSize >= 0 ? "已覆盖写入 " : "已创建 ")
                    .append(BuiltinToolkit.relative(config, path))
                    .append("：").append(bytes.length).append(" 字节，").append(lines).append(" 行。");
            if (previousSize >= 0) {
                message.append("（原文件 ").append(previousSize).append(" 字节）");
            }
            return ToolResult.ok(message.toString(), meta);
        } catch (IOException e) {
            return ToolResult.error("写入文件失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        } catch (RuntimeException e) {
            return ToolResult.error("写入文件时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        // 有副作用：同一批工具调用里并发写文件会让"谁覆盖了谁"变得不可预测。
        return false;
    }

    @Override
    public boolean requiresApproval() {
        // 写文件必须人工确认：这是本插件默认姿态"只读优先"的延伸。
        return true;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
        return "WriteTool(" + config.fence() + ")";
    }
}
