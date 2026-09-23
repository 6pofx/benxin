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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件查找工具（{@code glob}）：按 glob 表达式找文件，按最后修改时间倒序返回。
 *
 * <p>为什么按 mtime 倒序：模型找人名/文件名时，"最近改过的"几乎总是它想要的，
 * 而按字典序返回往往把噪声排在前面。</p>
 *
 * <p>为什么默认封顶 200 条：一次返回几千条路径，模型既读不完也只会浪费上下文；
 * 需要更精细的结果时应该收窄 pattern 或用 grep 找内容。</p>
 *
 * <p>遍历时会跳过 {@code .git / target / node_modules} 等目录（见
 * {@link BuiltinToolkit#SKIPPED_DIRS}），并且不跟随符号链接——
 * 既避免环，也避免顺着链接走到围栏外面去。</p>
 */
public class GlobTool implements ToolCallback {

    public static final String TOOL_NAME = "glob";

    /** 默认返回条数上限。 */
    private static final int MAX_RESULTS = 200;

    /** 一次遍历最多看多少个文件，防御超大目录树。 */
    private static final int MAX_SCAN_FILES = 50_000;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SandboxConfig config;
    private final ToolSpec spec;

    public GlobTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("GlobTool 需要非空的 SandboxConfig");
        }
        this.config = config;
        this.spec = buildSpec();
    }

    private static ToolSpec buildSpec() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode pattern = properties.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "glob 表达式，例如 **/*.java（递归）、*.md（仅当前层）");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "可选：在哪个子目录下查找，默认沙箱工作目录");

        schema.putArray("required").add("pattern");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "按 glob 表达式查找文件，返回相对路径、大小与最后修改时间（按修改时间倒序，最多 "
                        + MAX_RESULTS + " 条）。递归请用 **/*.java 这种写法。",
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
            String rawPattern = asString(args.get("pattern"));
            if (rawPattern == null || rawPattern.isBlank()) {
                return ToolResult.error("参数 pattern 必填：请给出 glob 表达式，例如 **/*.java。");
            }
            BuiltinToolkit.GlobPattern pattern;
            try {
                pattern = BuiltinToolkit.GlobPattern.compile(rawPattern);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }

            Path base = config.workdirAbsolute();
            String rawSub = asString(args.get("path"));
            if (rawSub != null && !rawSub.isBlank()) {
                PathSandbox.Resolution resolution = PathSandbox.resolve(config, rawSub);
                if (!resolution.allowed()) {
                    return ToolResult.error(resolution.rejectReason());
                }
                base = resolution.path();
                if (!Files.isDirectory(base)) {
                    return ToolResult.error("path 不是一个目录: " + rawSub);
                }
            }

            BuiltinToolkit.WalkResult walk = BuiltinToolkit.walkFiles(base, MAX_SCAN_FILES);
            List<Hit> hits = new ArrayList<>();
            int denied = 0;
            for (Path file : walk.files()) {
                Path relative = relativize(base, file);
                String relativeText = relative.toString().replace('\\', '/');
                if (!pattern.matchesPath(relativeText)) {
                    continue;
                }
                if (PathSandbox.isDeniedPath(config, file)) {
                    denied++; // 敏感路径连名字都不列出来
                    continue;
                }
                hits.add(new Hit(file, relativeText, size(file), modified(file)));
            }

            if (hits.isEmpty()) {
                return ToolResult.ok("未匹配到文件：pattern=" + pattern.pattern()
                        + "，搜索根目录=" + BuiltinToolkit.relative(config, base)
                        + (denied > 0 ? "（另有 " + denied + " 个敏感路径结果被沙箱规则过滤）" : "")
                        + "。可以放宽 pattern，或检查 path 是否指对了目录。");
            }

            hits.sort(Comparator.comparing(Hit::modified).reversed());
            int shown = Math.min(hits.size(), MAX_RESULTS);
            StringBuilder out = new StringBuilder();
            out.append("匹配到 ").append(hits.size()).append(" 个文件")
                    .append(hits.size() > shown ? "（按修改时间倒序显示前 " + shown + " 个）" : "")
                    .append("：\n");
            for (int i = 0; i < shown; i++) {
                Hit hit = hits.get(i);
                out.append(hit.relative()).append("  ")
                        .append(hit.size()).append(" B  ")
                        .append(TIME_FORMAT.format(hit.modified()))
                        .append('\n');
            }
            if (denied > 0) {
                out.append("（另有 ").append(denied).append(" 个敏感路径结果被沙箱规则过滤）\n");
            }
            if (walk.truncated()) {
                out.append("（目录扫描达到 ").append(MAX_SCAN_FILES)
                        .append(" 个文件上限，结果可能不完整，建议收窄 path）\n");
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("pattern", pattern.pattern());
            meta.put("base", BuiltinToolkit.relative(config, base));
            meta.put("matches", hits.size());
            meta.put("returned", shown);
            meta.put("scanTruncated", walk.truncated());

            String content = BuiltinToolkit.truncate(config, context == null ? null : context.sandbox(), out.toString());
            return ToolResult.ok(content, meta);
        } catch (RuntimeException e) {
            return ToolResult.error("查找文件时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public boolean requiresApproval() {
        return false; // 只读
    }

    private record Hit(Path file, String relative, long size, Instant modified) {
    }

    private static Path relativize(Path base, Path file) {
        try {
            return base.relativize(file);
        } catch (IllegalArgumentException e) {
            return file;
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static Instant modified(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return time.toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
        return "GlobTool(" + config.fence() + ")";
    }
}
