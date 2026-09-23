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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容检索工具（{@code grep}）：按正则逐文件逐行匹配，输出 {@code 相对路径:行号:内容}。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>输出行号，方便模型接着用 read 的 offset 精读；</li>
 *   <li>默认最多 100 条结果——grep 的典型用途是"定位"，而不是"把整个仓库读一遍"；</li>
 *   <li>跳过二进制文件（含 NUL 字节）、跳过构建产物目录、跳过敏感路径（默认规则）；</li>
 *   <li>超过单文件上限的文件只扫描前 {@link SandboxConfig#maxFileBytes()} 字节，
 *       并在结尾注明，避免"看起来没找到其实没扫"的错觉。</li>
 * </ul>
 */
public class GrepTool implements ToolCallback {

    public static final String TOOL_NAME = "grep";

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int HARD_MAX_RESULTS = 1000;

    /** 一次遍历最多看多少个文件，防御超大目录树。 */
    private static final int MAX_SCAN_FILES = 20_000;

    /** 单行输出上限，避免压缩过的 js 把上下文冲垮。 */
    private static final int MAX_LINE_CHARS = 1000;

    private final SandboxConfig config;
    private final ToolSpec spec;

    public GrepTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("GrepTool 需要非空的 SandboxConfig");
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
        pattern.put("description", "Java 正则表达式（子串匹配），例如 \"class\\s+\\w+Tool\"");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "可选：搜索哪个文件或子目录，默认沙箱工作目录");

        ObjectNode include = properties.putObject("include");
        include.put("type", "string");
        include.put("description", "可选：只搜索匹配该 glob 的文件，例如 *.java");

        ObjectNode ignoreCase = properties.putObject("ignore_case");
        ignoreCase.put("type", "boolean");
        ignoreCase.put("description", "是否忽略大小写，默认 false");

        ObjectNode maxResults = properties.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "最多返回多少条匹配，默认 " + DEFAULT_MAX_RESULTS);

        schema.putArray("required").add("pattern");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "在文件中按正则搜索内容，输出 相对路径:行号:该行内容。"
                        + "可用 include 限定文件名，用 path 限定目录。",
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
            if (rawPattern == null || rawPattern.isEmpty()) {
                return ToolResult.error("参数 pattern 必填：请给出要搜索的正则表达式。");
            }

            boolean ignoreCase = asBoolean(args.get("ignore_case"), false);
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            Pattern pattern;
            try {
                pattern = Pattern.compile(rawPattern, flags);
            } catch (PatternSyntaxException e) {
                return ToolResult.error("正则表达式非法: " + rawPattern + " —— " + e.getDescription()
                        + "（位置 " + e.getIndex() + "）。请修正后重试。");
            }

            BuiltinToolkit.GlobPattern include = null;
            String rawInclude = asString(args.get("include"));
            if (rawInclude != null && !rawInclude.isBlank()) {
                try {
                    include = BuiltinToolkit.GlobPattern.compile(rawInclude);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error("include 参数非法: " + e.getMessage());
                }
            }

            int maxResults = clamp(asInt(args.get("max_results"), DEFAULT_MAX_RESULTS));

            Path base = config.workdirAbsolute();
            String rawSub = asString(args.get("path"));
            if (rawSub != null && !rawSub.isBlank()) {
                PathSandbox.Resolution resolution = PathSandbox.resolve(config, rawSub);
                if (!resolution.allowed()) {
                    return ToolResult.error(resolution.rejectReason());
                }
                base = resolution.path();
                if (!Files.exists(base)) {
                    return ToolResult.error("path 不存在: " + rawSub);
                }
            }

            List<Path> candidates;
            boolean scanTruncated = false;
            if (Files.isRegularFile(base)) {
                candidates = List.of(base);
            } else {
                BuiltinToolkit.WalkResult walk = BuiltinToolkit.walkFiles(base, MAX_SCAN_FILES);
                candidates = walk.files();
                scanTruncated = walk.truncated();
            }

            List<String> lines = new ArrayList<>();
            int matchedFiles = 0;
            int skippedBinary = 0;
            int skippedDenied = 0;
            int truncatedFiles = 0;
            boolean limitReached = false;

            for (Path file : candidates) {
                if (limitReached) {
                    break;
                }
                String relative = BuiltinToolkit.relative(config, file);
                if (PathSandbox.isDeniedPath(config, file)) {
                    skippedDenied++;
                    continue;
                }
                String fileName = file.getFileName() == null ? relative : file.getFileName().toString();
                if (include != null && !include.matchesName(fileName, relative)) {
                    continue;
                }

                int remaining = maxResults - lines.size();
                // 多要一条：用来区分"正好搜完"和"其实还有更多"。
                ScanResult scan = scan(file, pattern, remaining + 1);
                if (scan.binary()) {
                    skippedBinary++;
                    continue;
                }
                if (scan.truncated()) {
                    truncatedFiles++;
                }
                if (scan.lines().isEmpty()) {
                    continue;
                }
                matchedFiles++;
                String prefix = relative + ":";
                List<String> hits = scan.lines();
                int take = Math.min(hits.size(), Math.max(0, remaining));
                for (int i = 0; i < take; i++) {
                    lines.add(prefix + hits.get(i));
                }
                if (hits.size() > remaining) {
                    limitReached = true;
                    break;
                }
            }

            if (lines.isEmpty()) {
                return ToolResult.ok("未找到匹配: /" + rawPattern + "/（搜索根目录 "
                        + BuiltinToolkit.relative(config, base) + "）"
                        + (skippedDenied > 0 ? "；" + skippedDenied + " 个敏感路径被沙箱规则跳过" : "")
                        + (skippedBinary > 0 ? "；" + skippedBinary + " 个二进制文件被跳过" : ""));
            }

            StringBuilder out = new StringBuilder();
            out.append("找到 ").append(lines.size()).append(" 处匹配，分布在 ").append(matchedFiles)
                    .append(" 个文件").append(limitReached ? "（已达 max_results 上限）" : "").append("：\n");
            for (String line : lines) {
                out.append(line).append('\n');
            }
            if (limitReached) {
                out.append("... [结果已达上限 ").append(maxResults).append("，请收窄 pattern 或 path] ...\n");
            }
            if (truncatedFiles > 0) {
                out.append("（").append(truncatedFiles).append(" 个文件超过 ")
                        .append(config.maxFileBytes()).append(" 字节，只扫描了前若干字节）\n");
            }
            if (skippedBinary > 0) {
                out.append("（跳过 ").append(skippedBinary).append(" 个二进制文件）\n");
            }
            if (skippedDenied > 0) {
                out.append("（跳过 ").append(skippedDenied).append(" 个敏感路径）\n");
            }
            if (scanTruncated) {
                out.append("（目录扫描达到 ").append(MAX_SCAN_FILES)
                        .append(" 个文件上限，结果可能不完整，建议收窄 path）\n");
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("pattern", rawPattern);
            meta.put("base", BuiltinToolkit.relative(config, base));
            meta.put("matches", lines.size());
            meta.put("files", matchedFiles);
            meta.put("limitReached", limitReached);

            String content = BuiltinToolkit.truncate(config, context == null ? null : context.sandbox(), out.toString());
            return ToolResult.ok(content, meta);
        } catch (RuntimeException e) {
            return ToolResult.error("搜索内容时发生异常: " + e.getClass().getSimpleName()
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

    /**
     * 扫描一个文件。
     *
     * @param budget 还需要多少条结果（达到即提前收工）
     */
    private ScanResult scan(Path file, Pattern pattern, int budget) {
        if (budget <= 0) {
            return new ScanResult(List.of(), false, false);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new ScanResult(List.of(), false, false);
        }
        long limit = Math.min(size, config.maxFileBytes());
        byte[] bytes = new byte[(int) Math.max(0, limit)];
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < bytes.length) {
                byte[] smaller = new byte[read];
                System.arraycopy(bytes, 0, smaller, 0, read);
                bytes = smaller;
            }
        } catch (IOException e) {
            return new ScanResult(List.of(), false, false);
        }

        if (looksBinary(bytes)) {
            return new ScanResult(List.of(), true, false);
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        String[] rawLines = text.split("\n", -1);
        for (int i = 0; i < rawLines.length && hits.size() < budget; i++) {
            String line = rawLines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                hits.add((i + 1) + ":" + clip(line.trim()));
            }
        }
        return new ScanResult(hits, false, size > limit);
    }

    private record ScanResult(List<String> lines, boolean binary, boolean truncated) {
    }

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
        return line.substring(0, MAX_LINE_CHARS) + " …（本行过长已截断）";
    }

    private static int clamp(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, HARD_MAX_RESULTS);
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

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof CharSequence text) {
            return Boolean.parseBoolean(text.toString().trim());
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "GrepTool(" + config.fence() + ")";
    }
}
