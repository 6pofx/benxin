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
 * 精确替换工具（{@code edit}）：字符串级改动，不做任何"猜你要改哪里"的事。
 *
 * <p>两条防误改规则（这是 Claude Code 的经典行为，能显著减少模型改错地方）：</p>
 * <ol>
 *   <li>{@code old_string} 找不到 → 直接报错，绝不悄悄新建或模糊匹配；</li>
 *   <li>{@code old_string} 命中多处且 {@code replace_all=false} → 直接报错并要求提供更长上下文，
 *       绝不"随手改第一处"。</li>
 * </ol>
 *
 * <p>附带一个实用细节：如果文件用 CRLF 换行而模型给的是 LF，会先按原样匹配，
 * 失败后再按文件的行尾风格重试一次，避免"换行符不一致"导致的假性找不到。</p>
 */
public class EditTool implements ToolCallback {

    public static final String TOOL_NAME = "edit";

    private final SandboxConfig config;
    private final ToolSpec spec;

    public EditTool(SandboxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("EditTool 需要非空的 SandboxConfig");
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
        path.put("description", "要修改的文件路径（相对沙箱工作目录）");

        ObjectNode oldString = properties.putObject("old_string");
        oldString.put("type", "string");
        oldString.put("description", "被替换的原文，必须与文件内容完全一致（含缩进）；"
                + "匹配到多处时请补足上下文");

        ObjectNode newString = properties.putObject("new_string");
        newString.put("type", "string");
        newString.put("description", "替换后的内容；删除代码时传空字符串");

        ObjectNode replaceAll = properties.putObject("replace_all");
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "是否替换全部匹配，默认 false（只允许唯一匹配）");

        schema.putArray("required").add("path").add("old_string").add("new_string");
        schema.put("additionalProperties", false);

        return new ToolSpec(TOOL_NAME,
                "把文件中的 old_string 精确替换为 new_string。默认要求唯一匹配；"
                        + "匹配到多处时会报错并要求提供更长的上下文或设置 replace_all=true。",
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
            String oldString = asString(args.get("old_string"));
            String newString = asString(args.get("new_string"));
            boolean replaceAll = asBoolean(args.get("replace_all"), false);

            if (rawPath == null || rawPath.isBlank()) {
                return ToolResult.error("参数 path 必填：请给出要修改的文件路径。");
            }
            if (oldString == null || oldString.isEmpty()) {
                return ToolResult.error("参数 old_string 必填且不能为空：请给出要在文件中精确匹配的原文。");
            }
            if (newString == null) {
                return ToolResult.error("参数 new_string 必填：删除内容时请显式传空字符串。");
            }
            if (oldString.equals(newString)) {
                return ToolResult.error("old_string 与 new_string 完全相同，无需修改。");
            }
            if (!config.allowWrite()) {
                // 纵深防御：只读配置下连磁盘都不碰。
                return ToolResult.error("当前沙箱 allowWrite=false（默认只读），拒绝修改 " + rawPath + "。"
                        + "开启方式：SandboxConfig.builder().allowWrite(true).build()。");
            }

            PathSandbox.Resolution resolution = PathSandbox.resolve(config, rawPath);
            if (!resolution.allowed()) {
                return ToolResult.error(resolution.rejectReason());
            }
            Path path = resolution.path();
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在: " + rawPath + "。edit 只能修改已存在的文件，"
                        + "新建文件请用 write。");
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error("[" + rawPath + "] 是一个目录，edit 只能改文件。");
            }
            long size = Files.size(path);
            if (size > config.maxFileBytes()) {
                return ToolResult.error("文件过大（" + size + " 字节，超过上限 " + config.maxFileBytes()
                        + " 字节），已拒绝编辑。请先缩小范围或调整 SandboxConfig.maxFileBytes。");
            }

            byte[] bytes = Files.readAllBytes(path);
            if (looksBinary(bytes)) {
                return ToolResult.error("[" + rawPath + "] 看起来是二进制文件，已拒绝编辑。");
            }
            String original = new String(bytes, StandardCharsets.UTF_8);

            String target = oldString;
            String replacement = newString;
            int count = countOccurrences(original, target);
            if (count == 0 && original.contains("\r\n") && !target.contains("\r")) {
                // 文件是 CRLF、模型给的是 LF：按文件的行尾风格重试一次，保持行尾不被改写。
                String crlfTarget = target.replace("\n", "\r\n");
                if (countOccurrences(original, crlfTarget) > 0) {
                    target = crlfTarget;
                    replacement = replacement.replace("\n", "\r\n");
                    count = countOccurrences(original, target);
                }
            }

            if (count == 0) {
                return ToolResult.error("未找到待替换片段：old_string 在 " + rawPath + " 中不存在。"
                        + "请先用 read 确认原文（注意缩进与换行必须完全一致），再重试。");
            }
            if (count > 1 && !replaceAll) {
                return ToolResult.error("匹配到 " + count + " 处，请提供更长的上下文或设置 replace_all=true。"
                        + "（当前 replace_all=false，为避免误改已拒绝执行）");
            }

            String updated = replaceAll
                    ? original.replace(target, replacement)
                    : replaceOnce(original, target, replacement);
            int replaced = replaceAll ? count : 1;

            Files.write(path, updated.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", TOOL_NAME);
            meta.put("path", BuiltinToolkit.relative(config, path));
            meta.put("replacements", replaced);
            meta.put("bytes", updated.getBytes(StandardCharsets.UTF_8).length);
            meta.put("previousBytes", size);

            return ToolResult.ok("已编辑 " + BuiltinToolkit.relative(config, path) + "：替换 " + replaced
                    + " 处，" + size + " → " + meta.get("bytes") + " 字节。", meta);
        } catch (IOException e) {
            return ToolResult.error("编辑文件失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        } catch (RuntimeException e) {
            return ToolResult.error("编辑文件时发生异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    @Override
    public boolean parallelSafe() {
        return false; // 同一批里并发改同一个文件会互相覆盖
    }

    @Override
    public boolean requiresApproval() {
        return true; // 改文件必须人工确认
    }

    /** 统计互不重叠的出现次数。 */
    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String replaceOnce(String text, String target, String replacement) {
        int index = text.indexOf(target);
        if (index < 0) {
            return text;
        }
        return text.substring(0, index) + replacement + text.substring(index + target.length());
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

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
        return "EditTool(" + config.fence() + ")";
    }
}
