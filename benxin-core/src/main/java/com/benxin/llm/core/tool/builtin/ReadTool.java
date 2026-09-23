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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取文件工具（{@code read}）：按行号输出，行为对齐 {@code cat -n}。
 *
 * <p>为什么输出行号：模型一旦能引用"第 42 行"，后续的 edit 就能给出精确锚点，
 * 少掉大量"我猜你说的是哪一段"的往返。</p>
 *
 * <p>三道自我保护：路径必须落在沙箱围栏内（{@link PathSandbox#resolve}）、
 * 超过 {@link SandboxConfig#maxFileBytes()} 直接拒绝、含 NUL 字节的二进制文件直接拒绝。
 * 所有失败都返回 {@link ToolResult#error}，绝不抛异常——把原因交给模型，它才有机会自我纠正。</p>
 */
public class ReadTool implements ToolCallback {

    public static final String TOOL_NAME = "read";

    /** 默认读取行数：够看一个完整类文件，又不至于一次吃掉太多上下文。 */
    private static final int DEFAULT_LIMIT = 2000;

    /** 单行最大长度：压缩过的 js / 超长日志行会毁掉上下文，截断并标注。 */
    private static final int MAX_LINE_CHARS = 2000;

    private final SandboxConfig config;
    private final ToolSpec spec;

    public ReadTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ReadTool 需要非空的 SandboxConfig");
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
        path.put("description", "要读取的文件路径，相对于沙箱工作目录（也接受围栏内的绝对路径）");

        ObjectNode offset = properties.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "起始行号，从 1 开始；默认 1");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "最多读取多少行，默认 " + DEFAULT_LIMIT);

        schema.putArray("required").add("path");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "读取文本文件，输出带行号的 cat -n 风格内容。支持 offset/limit 分段读取；"
                        + "二进制文件与超大文件会被拒绝。",
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
                return ToolResult.error("参数 path 必填：请给出要读取的文件路径（相对沙箱工作目录）。");
            }

            PathSandbox.Resolution resolution = PathSandbox.resolve(config, rawPath);
            if (!resolution.allowed()) {
                return ToolResult.error(resolution.rejectReason());
            }
            Path path = resolution.path();
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在: " + rawPath + "（解析为 " + path + "）。"
                        + "可以用 glob 或 grep 先确认路径。");
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error("[" + rawPath + "] 是一个目录，read 只能读文件。"
                        + "请用 glob 列出其中文件，或直接读具体文件。");
            }
            if (!Files.isReadable(path)) {
                return ToolResult.error("文件不可读（权限不足）: " + rawPath);
            }

            long size = Files.size(path);
            if (size > config.maxFileBytes()) {
                return ToolResult.error("文件过大：" + rawPath + " 为 " + humanSize(size)
                        + "，超过单文件读取上限 " + humanSize(config.maxFileBytes()) + "。"
                        + "建议用 grep 定位片段（或提高 SandboxConfig.maxFileBytes）。");
            }

            byte[] bytes = Files.readAllBytes(path);
            if (looksBinary(bytes)) {
                return ToolResult.error("[" + rawPath + "] 看起来是二进制文件（含 NUL 字节），已拒绝读取。"
                        + "如需查看请改用 bash 配合专用工具。");
            }

            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(text.lines().toList());
            int total = lines.size();

            int offsetValue = asInt(args.get("offset"), 1);
            int limitValue = asInt(args.get("limit"), DEFAULT_LIMIT);
            if (offsetValue < 1) {
                return ToolResult.error("参数 offset 必须从 1 开始，收到: " + offsetValue);
            }
            if (limitValue < 1) {
                return ToolResult.error("参数 limit 必须为正数，收到: " + limitValue);
            }

            if (total == 0) {
                return ToolResult.ok("(文件为空) " + rawPath, meta(rawPath, 0, 0, 0, size));
            }
            if (offsetValue > total) {
                return ToolResult.ok("文件 " + rawPath + " 共 " + total + " 行，offset=" + offsetValue
                        + " 已超出范围，没有内容可显示。", meta(rawPath, 0, total, offsetValue, size));
            }

            int from = offsetValue;
            int to = (int) Math.min((long) total, (long) from - 1 + limitValue);
            StringBuilder out = new StringBuilder();
            for (int i = from; i <= to; i++) {
                out.append(String.format("%6d\t%s", i, clip(lines.get(i - 1)))).append('\n');
            }
            int shown = to - from + 1;
            if (to < total) {
                out.append("\n... [文件共 ").append(total).append(" 行，本次显示 ").append(from)
                        .append('-').append(to).append(" 行；继续读取请用 offset=").append(to + 1)
                        .append("] ...");
            }

            String content = BuiltinToolkit.truncate(config, context == null ? null : context.sandbox(), out.toString());
            return ToolResult.ok(content, meta(rawPath, shown, total, from, size));
        } catch (IOException e) {
            return ToolResult.error("读取文件失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        } catch (RuntimeException e) {
            return ToolResult.error("读取文件时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public boolean requiresApproval() {
        return false; // 只读，不需要人点头
    }

    private Map<String, Object> meta(String path, int shown, int total, int offset, long size) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", TOOL_NAME);
        meta.put("path", path);
        meta.put("linesShown", shown);
        meta.put("linesTotal", total);
        meta.put("offset", offset);
        meta.put("bytes", size);
        return meta;
    }

    /** 前 8000 字节里出现 NUL 就按二进制处理（文本文件不会有 NUL）。 */
    private static boolean looksBinary(byte[] bytes) {
        int scan = Math.min(bytes.length, 8000);
        for (int i = 0; i < scan; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS) + " …（本行过长已截断，共 " + line.length() + " 字符）";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Integer.parseInt(text.toString().trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "ReadTool(" + config.fence() + ")";
    }
}
