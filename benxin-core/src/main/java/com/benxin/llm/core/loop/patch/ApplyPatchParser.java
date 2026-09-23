package com.benxin.llm.core.loop.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex 风格补丁（{@code *** Begin Patch} 格式）的解析与应用器。
 *
 * <p>设计来源：Codex / {@code apply_patch} 工具采用的补丁格式。它比 unified diff 更啰嗦一点，
 * 但对 LLM 更友好——段落标记是自然语言（{@code *** Add File:} / {@code *** Update File:}），
 * 不需要模型正确计算 {@code @@ -a,b +c,d @@} 的行号，因此模型写错行号的概率大幅下降。</p>
 *
 * <p>关键取舍（为什么不用 {@code git apply} / 现成 diff 库）：</p>
 * <ol>
 *   <li><b>不引入依赖</b>：本心坚持只用 Jackson + SLF4J，补丁解析必须自己写。</li>
 *   <li><b>不用行号，用上下文匹配</b>：模型给的行号经常不准，因此定位靠"上下文行 + 删除行"
 *       构成的序列在整个文件里做<b>唯一匹配</b>；匹配到 0 处或多处都算失败并给出可操作的原因，
 *       绝不"猜一个位置改上去"——改错位置比不改还糟。</li>
 *   <li><b>失败不抛异常</b>：模型写错补丁是常态，解析与应用的诊断信息要回灌给模型让它自我修正，
 *       所以 {@link #parse(String)} 与 {@link #apply(String, Path, boolean)} 都只返回诊断，不抛异常。</li>
 *   <li><b>路径围栏是安全边界</b>：所有路径 {@code normalize()} 后必须仍在 {@code root} 之下；
 *       只要有任何一个路径越界，整个补丁一律不落盘（fail-closed），避免"一半合法一半越界"的
 *       补丁被部分执行。</li>
 *   <li><b>保留原换行符</b>：Windows 项目里若把 CRLF 文件改写成 LF，git 会把整个文件算作改动，
 *       产生巨大的噪音 diff，评审时根本看不出真正的改动。因此应用补丁时先探测该文件原有的
 *       换行风格，写回时沿用；新建文件统一使用 {@code \n}。</li>
 * </ol>
 *
 * <p>本类为纯静态工具类，线程安全，不持有任何状态。内部类型（{@link PatchOp}、{@link Hunk} 等）
 * 全部作为嵌套类型提供，便于用户在不新增文件的前提下复用解析结果。</p>
 */
public final class ApplyPatchParser {

    /** 段落开始标记。 */
    private static final String BEGIN_PATCH = "*** Begin Patch";
    /** 段落结束标记。 */
    private static final String END_PATCH = "*** End Patch";
    private static final String ADD_FILE = "*** Add File:";
    private static final String UPDATE_FILE = "*** Update File:";
    private static final String DELETE_FILE = "*** Delete File:";
    private static final String MOVE_TO = "*** Move to:";
    private static final String HUNK_HEADER = "@@";
    /** unified diff 里的"文件末尾没有换行"标记，忽略即可。 */
    private static final String NO_NEWLINE = "\\ No newline at end of file";

    /** 单个 hunk 允许的最大行数，超过则拒绝：上下文匹配是 O(文件行数 × hunk 行数)，需要设上限。 */
    private static final int MAX_HUNK_LINES = 2_000;

    /** 提示行里可能出现的行号范围，例如 {@code -12,7 +12,9}。 */
    private static final Pattern HINT_RANGE = Pattern.compile("^-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?");

    private ApplyPatchParser() {
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一次补丁操作。 */
    public sealed interface PatchOp permits AddFile, DeleteFile, UpdateFile {

        /** 补丁里书写的原始路径（相对路径或绝对路径，尚未做围栏校验）。 */
        Path path();
    }

    /**
     * 新增文件。
     *
     * @param path  目标路径
     * @param lines 文件内容行（不含换行符）
     */
    public record AddFile(Path path, List<String> lines) implements PatchOp {

        public AddFile {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 删除文件。 */
    public record DeleteFile(Path path) implements PatchOp {
    }

    /**
     * 修改已有文件。
     *
     * @param path  目标路径
     * @param hunks 若干处修改
     */
    public record UpdateFile(Path path, List<Hunk> hunks) implements PatchOp {

        public UpdateFile {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
        }
    }

    /**
     * 一处修改。
     *
     * @param contextHint {@code @@} 行给出的定位提示，可能为 {@code null}
     * @param lines       hunk 正文（上下文行 / 新增行 / 删除行，按原顺序）
     */
    public record Hunk(String contextHint, List<Line> lines) {

        public Hunk {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** 定位用的序列：上下文行 + 删除行，按原顺序。 */
        public List<String> beforeLines() {
            List<String> before = new ArrayList<>();
            for (Line line : lines) {
                if (line.type() != Line.Type.ADD) {
                    before.add(line.text());
                }
            }
            return before;
        }

        /** 替换用的序列：上下文行 + 新增行（上下文行必须原样保留）。 */
        public List<String> afterLines() {
            List<String> after = new ArrayList<>();
            for (Line line : lines) {
                if (line.type() != Line.Type.REMOVE) {
                    after.add(line.text());
                }
            }
            return after;
        }

        /** 该 hunk 是否真的会改动内容（只有上下文行 = 空操作）。 */
        public boolean mutates() {
            return lines.stream().anyMatch(l -> l.type() != Line.Type.CONTEXT);
        }
    }

    /**
     * hunk 里的一行。
     *
     * @param type 行类型
     * @param text 去掉前缀后的文本
     */
    public record Line(Type type, String text) {

        public Line {
            type = type == null ? Type.CONTEXT : type;
            text = text == null ? "" : text;
        }

        /** 行类型：上下文 / 新增 / 删除。 */
        public enum Type {
            CONTEXT,
            ADD,
            REMOVE
        }
    }

    /**
     * 解析结果。
     *
     * @param ops    解析出的操作（只含格式正确的部分）
     * @param errors 诊断信息；非空表示有段落被丢弃或格式可疑
     */
    public record ParseResult(List<PatchOp> ops, List<String> errors) {

        public ParseResult {
            ops = ops == null ? List.of() : List.copyOf(ops);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean isEmpty() {
            return ops.isEmpty();
        }
    }

    /**
     * 应用结果。
     *
     * @param success      是否完全成功（{@code errors} 为空）
     * @param filesChanged 实际（或试运行下将会）发生变化的文件数
     * @param messages     成功信息（中文，可直接回灌给模型）
     * @param errors       失败信息（中文，说明原因与建议动作）
     */
    public record Result(boolean success, int filesChanged, List<String> messages, List<String> errors) {

        public Result {
            messages = messages == null ? List.of() : List.copyOf(messages);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** 拼成一段可直接作为工具结果回灌给模型的文本。 */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            if (success) {
                sb.append("补丁应用成功，共影响 ").append(filesChanged).append(" 个文件。\n");
            } else {
                sb.append("补丁未完全应用：成功 ").append(filesChanged).append(" 个文件，")
                        .append(errors.size()).append(" 个问题。\n");
            }
            for (String message : messages) {
                sb.append("  · ").append(message).append('\n');
            }
            for (String error : errors) {
                sb.append("  ! ").append(error).append('\n');
            }
            return sb.toString();
        }
    }

    // ==================================================================
    // 格式说明
    // ==================================================================

    /**
     * 补丁格式的中文说明，用于拼进工具描述（让模型知道该怎么写补丁）。
     *
     * <p>系统提示词侧的同类说明见 {@code PromptTemplates.CODEX_PATCH} 与
     * {@code PromptTemplates.CODEX_PATCH_FORMAT_EXAMPLE}：那一份面向"模型行为约束"，
     * 这一份面向"工具参数格式"，两者内容一致、措辞各自贴合场景。</p>
     */
    public static String describeFormat() {
        return """
                补丁格式（apply_patch 的输入就是这段纯文本，必须原样包含段落标记）：

                *** Begin Patch
                *** Add File: 相对/路径/New.java
                +第一行
                +第二行
                *** Update File: 相对/路径/Existing.java
                @@ 可选的定位提示（类名、方法名或 -行号,数量 +行号,数量）
                 以空格开头的行是上下文行，必须与文件中的内容逐字符一致
                -被删除的行
                +新增的行
                *** Delete File: 相对/路径/Old.java
                *** End Patch

                规则：
                1. 整段补丁必须被 *** Begin Patch 与 *** End Patch 包住。
                2. 每个文件一段，段首用 *** Add File: / *** Update File: / *** Delete File: 声明。
                3. Add File 段内每一行都以 + 开头（内容为 + 之后的部分；单独一个 + 表示空行）。
                4. Update File 段内用 @@ 分隔多个 hunk；hunk 内以空格开头为上下文行、- 为删除行、+ 为新增行。
                5. 每个 hunk 必须带足上下文（改动点前后各 2-3 行），且"上下文+删除行"在文件中只能出现一次，
                   否则会因无法唯一定位而失败。
                6. 路径只能是工作目录下的相对路径，不允许 .. 或绝对路径。
                7. 不要用补丁重写整个文件；补丁越小越容易一次成功。""";
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * 解析补丁文本。
     *
     * <p>宽松策略：{@code *** Begin Patch} / {@code *** End Patch} 缺失时仍按段落标记解析
     * （模型经常漏写包围标记），标记之外的散文、代码围栏（```）会被忽略；但任何"看起来想表达
     * 某个文件操作却写坏了"的段落都会记进 {@code errors}，并丢掉该段。</p>
     */
    public static ParseResult parse(String patchText) {
        List<PatchOp> ops = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (patchText == null || patchText.isBlank()) {
            errors.add("补丁内容为空：请提供 *** Begin Patch ... *** End Patch 之间的文本。");
            return new ParseResult(ops, errors);
        }

        String[] lines = normalize(patchText).split("\n", -1);
        Section section = Section.NONE;
        String path = null;
        List<String> addLines = new ArrayList<>();
        List<String> deleteLines = new ArrayList<>(); // Delete File 段的内容行（应为空，非空说明模型写错了）
        List<Hunk> hunks = new ArrayList<>();
        List<Line> current = null;
        String contextHint = null;
        int pendingBlanks = 0;
        int sectionStart = 0;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();

            if (trimmed.equals(BEGIN_PATCH) || trimmed.equals(END_PATCH)) {
                continue;
            }
            if (trimmed.startsWith(ADD_FILE) || trimmed.startsWith(UPDATE_FILE)
                    || trimmed.startsWith(DELETE_FILE) || trimmed.startsWith(MOVE_TO)) {
                // 段落切换前必须先把"正在书写的 hunk"收进当前段落：
                // 最后一个 hunk 后面不会再有 @@ 行，漏掉这一步就会静默丢掉 Update 段的最后一个修改。
                if (section == Section.UPDATE && current != null && !current.isEmpty()) {
                    hunks.add(new Hunk(contextHint, current));
                }
                // 收束上一段
                flush(ops, errors, section, path, addLines, hunks, deleteLines, sectionStart);
                path = null;
                addLines.clear();
                hunks.clear();
                deleteLines.clear();
                current = null;
                contextHint = null;
                pendingBlanks = 0;
                sectionStart = i + 1;

                if (trimmed.startsWith(ADD_FILE)) {
                    section = Section.ADD;
                    path = pathOf(trimmed, ADD_FILE, errors, i + 1);
                } else if (trimmed.startsWith(UPDATE_FILE)) {
                    section = Section.UPDATE;
                    path = pathOf(trimmed, UPDATE_FILE, errors, i + 1);
                } else if (trimmed.startsWith(DELETE_FILE)) {
                    section = Section.DELETE;
                    path = pathOf(trimmed, DELETE_FILE, errors, i + 1);
                } else {
                    section = Section.UNSUPPORTED;
                    errors.add("第 " + (i + 1) + " 行：" + MOVE_TO + " （移动到新路径）不受支持，"
                            + "请拆成 *** Add File: 新路径 与 *** Delete File: 旧路径 两步。该段已忽略。");
                }
                continue;
            }

            if (section == Section.NONE) {
                // 段落标记之外的散文/代码围栏，静默忽略
                continue;
            }
            if (section == Section.UNSUPPORTED) {
                continue;
            }

            if (section == Section.UPDATE && trimmed.startsWith(HUNK_HEADER)) {
                if (current != null && !current.isEmpty()) {
                    hunks.add(new Hunk(contextHint, current));
                }
                current = new ArrayList<>();
                contextHint = hintOf(trimmed);
                pendingBlanks = 0;
                continue;
            }

            switch (section) {
                case ADD -> {
                    if (isFence(trimmed)) {
                        continue;
                    }
                    if (raw.startsWith("+")) {
                        addLines.add(raw.substring(1));
                    } else if (raw.isBlank()) {
                        // 容忍模型把"空行"写成真正的空行
                        addLines.add("");
                    } else {
                        errors.add("第 " + (i + 1) + " 行：Add File 段内的内容行必须以 + 开头（空行写 +），"
                                + "该行已按原样加入。");
                        addLines.add(raw);
                    }
                }
                case DELETE -> {
                    if (isFence(trimmed)) {
                        continue;
                    }
                    if (!raw.isBlank()) {
                        deleteLines.add(raw);
                    }
                }
                case UPDATE -> {
                    // 空行有歧义：可能是 hunk 内的空上下文行，也可能是段落之间的分隔。
                    // 先挂起，等下一行确认：若下一行是段落标记/hunk 标记就丢弃，否则补成空上下文行。
                    if (raw.isEmpty() || raw.isBlank()) {
                        pendingBlanks++;
                        continue;
                    }
                    if (isFence(trimmed)) {
                        // 模型常把整段补丁包在 ``` 代码围栏里：裸围栏行不是补丁内容，直接忽略
                        pendingBlanks = 0;
                        continue;
                    }
                    if (current == null) {
                        // @@ 之前出现的内容行：当成隐式 hunk，便于容忍漏写 @@ 的补丁
                        current = new ArrayList<>();
                        contextHint = null;
                    }
                    if (pendingBlanks > 0) {
                        for (int b = 0; b < pendingBlanks; b++) {
                            current.add(new Line(Line.Type.CONTEXT, ""));
                        }
                        pendingBlanks = 0;
                    }
                    if (raw.startsWith("+")) {
                        current.add(new Line(Line.Type.ADD, raw.substring(1)));
                    } else if (raw.startsWith("-")) {
                        current.add(new Line(Line.Type.REMOVE, raw.substring(1)));
                    } else if (raw.startsWith(" ")) {
                        current.add(new Line(Line.Type.CONTEXT, raw.substring(1)));
                    } else if (raw.startsWith("\\")) {
                        // "\ No newline at end of file" 一类标记，忽略
                    } else {
                        // 模型忘了写前缀：按上下文行处理，并给一条诊断
                        errors.add("第 " + (i + 1) + " 行：hunk 内的行必须以空格（上下文）、-（删除）或 +（新增）开头，"
                                + "已按上下文行处理。");
                        current.add(new Line(Line.Type.CONTEXT, raw));
                    }
                }
                default -> {
                    // 不会到达
                }
            }
        }

        if (current != null && !current.isEmpty()) {
            hunks.add(new Hunk(contextHint, current));
        }
        flush(ops, errors, section, path, addLines, hunks, deleteLines, sectionStart);

        if (ops.isEmpty() && errors.isEmpty()) {
            errors.add("补丁中没有可识别的文件段落：请用 *** Add File: / *** Update File: / *** Delete File: 声明。");
        }
        return new ParseResult(ops, errors);
    }

    // ==================================================================
    // 应用
    // ==================================================================

    /**
     * 解析并应用补丁。
     *
     * @param patchText 补丁文本
     * @param root      工作目录（所有路径必须落在它之下）
     * @param dryRun    true 时只做校验与定位，不写盘
     */
    public static Result apply(String patchText, Path root, boolean dryRun) {
        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (root == null) {
            errors.add("工作目录 root 不能为空，已拒绝应用补丁。");
            return new Result(false, 0, messages, errors);
        }
        Path base = root.toAbsolutePath().normalize();
        Path realBase = realPathOrSelf(base);

        ParseResult parsed = parse(patchText);
        errors.addAll(parsed.errors());
        if (parsed.ops().isEmpty()) {
            return new Result(false, 0, messages, errors);
        }

        // ---- 安全边界：先做整体路径围栏校验，任何一个越界都 fail-closed ----
        List<String> escapes = new ArrayList<>();
        for (PatchOp op : parsed.ops()) {
            Path resolved = resolveInside(realBase, op.path());
            if (resolved == null) {
                escapes.add(op.path() + " 不在工作目录 [" + base + "] 之下，已拒绝该路径。");
            }
        }
        if (!escapes.isEmpty()) {
            errors.addAll(escapes);
            errors.add("出于安全考虑：补丁中存在越界路径时整份补丁都不会被应用（包括其中合法的文件）。");
            return new Result(false, 0, messages, errors);
        }

        int filesChanged = 0;
        for (PatchOp op : parsed.ops()) {
            Path target = resolveInside(realBase, op.path());
            if (target == null) {
                errors.add("路径越界：" + op.path());
                continue;
            }
            if (op instanceof AddFile add) {
                if (applyAdd(add, target, base, dryRun, messages, errors)) {
                    filesChanged++;
                }
            } else if (op instanceof DeleteFile delete) {
                if (applyDelete(delete, target, base, dryRun, messages, errors)) {
                    filesChanged++;
                }
            } else if (op instanceof UpdateFile update) {
                if (applyUpdate(update, target, base, dryRun, messages, errors)) {
                    filesChanged++;
                }
            }
        }

        boolean success = errors.isEmpty();
        if (dryRun && filesChanged > 0 && success) {
            messages.add("（试运行模式：以上改动均已定位成功，但没有写入磁盘。）");
        }
        return new Result(success, filesChanged, messages, errors);
    }

    // ---- 各操作的落地 ----

    private static boolean applyAdd(AddFile add, Path target, Path base, boolean dryRun,
                                    List<String> messages, List<String> errors) {
        String shown = show(base, target);
        if (Files.exists(target)) {
            errors.add("Add File 失败：[" + shown + "] 已存在。新增文件不允许覆盖已有文件；"
                    + "若确实要改它，请改用 *** Update File:。");
            return false;
        }
        if (add.lines().isEmpty()) {
            errors.add("Add File 失败：[" + shown + "] 没有任何内容行（每行需以 + 开头，空行写单个 +）。");
            return false;
        }
        // 新建文件统一使用 LF：没有"原风格"可继承，而 LF 在各平台与 git 下最不易产生噪音
        String content = String.join("\n", add.lines()) + "\n";
        if (!dryRun) {
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                errors.add("Add File 失败：[" + shown + "] 写入出错：" + e.getMessage());
                return false;
            }
        }
        messages.add((dryRun ? "试运行：将新增" : "已新增") + " [" + shown + "]，"
                + add.lines().size() + " 行。");
        return true;
    }

    private static boolean applyDelete(DeleteFile delete, Path target, Path base, boolean dryRun,
                                       List<String> messages, List<String> errors) {
        String shown = show(base, target);
        if (!Files.exists(target)) {
            errors.add("Delete File 失败：[" + shown + "] 不存在。请先确认路径，不要凭印象删文件。");
            return false;
        }
        if (Files.isDirectory(target)) {
            errors.add("Delete File 失败：[" + shown + "] 是目录。本工具只删除文件，不递归删除目录。");
            return false;
        }
        if (!dryRun) {
            try {
                Files.delete(target);
            } catch (IOException e) {
                errors.add("Delete File 失败：[" + shown + "] 删除出错：" + e.getMessage());
                return false;
            }
        }
        messages.add((dryRun ? "试运行：将删除" : "已删除") + " [" + shown + "]。");
        return true;
    }

    private static boolean applyUpdate(UpdateFile update, Path target, Path base, boolean dryRun,
                                       List<String> messages, List<String> errors) {
        String shown = show(base, target);
        if (!Files.exists(target)) {
            errors.add("Update File 失败：[" + shown + "] 不存在。新增文件请用 *** Add File:。");
            return false;
        }
        if (update.hunks().isEmpty()) {
            errors.add("Update File 失败：[" + shown + "] 没有任何 hunk（用 @@ 分隔，hunk 内以空格/-/+ 开头）。");
            return false;
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("Update File 失败：[" + shown + "] 读取出错：" + e.getMessage());
            return false;
        }

        // 换行风格探测：混用换行符会让 git 把整个文件视为改动，必须在写回时沿用原风格
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithNewline = original.endsWith("\n") || original.endsWith("\r");
        List<String> lines = toLines(original);

        int applied = 0;
        int index = 0;
        for (Hunk hunk : update.hunks()) {
            index++;
            if (!hunk.mutates()) {
                errors.add("[" + shown + "] 第 " + index + " 个 hunk 只有上下文行、没有 - 或 + 行，"
                        + "不知道该改什么，已跳过。");
                continue;
            }
            List<String> before = hunk.beforeLines();
            if (before.isEmpty()) {
                errors.add("[" + shown + "] 第 " + index + " 个 hunk 没有任何上下文行或删除行，无法定位，已跳过。"
                        + "请为每个 hunk 补上前后各 2-3 行上下文。");
                continue;
            }
            if (before.size() > MAX_HUNK_LINES) {
                errors.add("[" + shown + "] 第 " + index + " 个 hunk 过大（" + before.size()
                        + " 行），请拆成多个小 hunk 再重试。");
                continue;
            }
            List<Integer> candidates = locate(lines, before, hunk.contextHint());
            if (candidates.isEmpty()) {
                errors.add("[" + shown + "] 第 " + index + " 个 hunk 在文件中找不到匹配的上下文"
                        + locateHint(hunk.contextHint()) + "。可能文件已被改动，或上下文行与文件内容不完全一致"
                        + "（空格、缩进、换行都算）。请重新读取该文件后重写这个 hunk。");
                continue;
            }
            if (candidates.size() > 1) {
                errors.add("[" + shown + "] 第 " + index + " 个 hunk 的上下文匹配到 " + candidates.size()
                        + " 处（行 " + previewLines(candidates, before.size()) + "），无法唯一确定要改的位置，已跳过。"
                        + "请增加上下文行，或用 @@ 给出更具体的定位提示。");
                continue;
            }
            int at = candidates.get(0);
            List<String> after = hunk.afterLines();
            lines.subList(at, at + before.size()).clear();
            lines.addAll(at, after);
            applied++;
        }

        if (applied == 0) {
            errors.add("[" + shown + "] 所有 hunk 都未能应用，文件保持原样。");
            return false;
        }

        if (!dryRun) {
            String updated = String.join(newline, lines) + (endsWithNewline ? newline : "");
            try {
                Files.writeString(target, updated, StandardCharsets.UTF_8);
            } catch (IOException e) {
                errors.add("Update File 失败：[" + shown + "] 写入出错：" + e.getMessage());
                return false;
            }
        }
        messages.add((dryRun ? "试运行：将修改" : "已修改") + " [" + shown + "]，成功应用 "
                + applied + "/" + update.hunks().size() + " 个 hunk。");
        return true;
    }

    /**
     * 在文件行里定位"上下文 + 删除"序列。
     *
     * @return 所有匹配的起始下标（0 个 = 没找到，多个 = 有歧义，调用方据此报错）
     */
    private static List<Integer> locate(List<String> lines, List<String> before, String contextHint) {
        int span = before.size();
        if (span == 0 || lines.size() < span) {
            return List.of();
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i + span <= lines.size(); i++) {
            if (matchesAt(lines, before, i)) {
                all.add(i);
            }
        }
        if (all.size() <= 1) {
            return all;
        }
        // 有歧义时，用 @@ 提示缩小范围：先按行号就近筛选，再按提示文本命中筛选
        List<Integer> narrowed = narrowByLineNumber(all, contextHint);
        if (narrowed.size() == 1) {
            return narrowed;
        }
        List<Integer> byText = narrowByText(lines, narrowed, contextHint, span);
        return byText.isEmpty() ? narrowed : byText;
    }

    private static boolean matchesAt(List<String> lines, List<String> before, int start) {
        for (int k = 0; k < before.size(); k++) {
            if (!lines.get(start + k).equals(before.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> narrowByLineNumber(List<Integer> candidates, String contextHint) {
        Integer hintLine = hintLineNumber(contextHint);
        if (hintLine == null) {
            return candidates;
        }
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate : candidates) {
            int distance = Math.abs(candidate + 1 - hintLine);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best < 0 ? candidates : List.of(best);
    }

    private static List<Integer> narrowByText(List<String> lines, List<Integer> candidates,
                                              String contextHint, int span) {
        String hintText = hintText(contextHint);
        if (hintText == null) {
            return List.of();
        }
        List<Integer> hits = new ArrayList<>();
        for (int candidate : candidates) {
            int from = Math.max(0, candidate - 40);
            int to = Math.min(lines.size(), candidate + span + 40);
            for (int i = from; i < to; i++) {
                if (lines.get(i).contains(hintText)) {
                    hits.add(candidate);
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 从 {@code @@ ...} 行里取出定位提示。
     *
     * <p>原样保留 {@code -12,7 +12,9} 这类行号范围：应用时既能用它就近定位，
     * 也能用其后的文字（unified diff 的 section heading）做二次筛选，因此不做预裁剪。</p>
     */
    private static String hintOf(String hunkHeaderLine) {
        String rest = hunkHeaderLine.substring(HUNK_HEADER.length()).strip();
        return rest.isEmpty() ? null : rest;
    }

    /** 解析 {@code -12,7 +12,9} 里的起始行号（1 基）。 */
    private static Integer hintLineNumber(String contextHint) {
        if (contextHint == null || contextHint.isBlank()) {
            return null;
        }
        Matcher matcher = HINT_RANGE.matcher(contextHint.strip());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hintText(String contextHint) {
        if (contextHint == null || contextHint.isBlank()) {
            return null;
        }
        String text = contextHint.strip();
        Matcher matcher = HINT_RANGE.matcher(text);
        if (matcher.find()) {
            text = text.substring(matcher.end()).strip();
            if (text.startsWith("@@")) {
                text = text.substring(2).strip();
            }
        }
        return text.isEmpty() ? null : text;
    }

    private static String locateHint(String contextHint) {
        return contextHint == null || contextHint.isBlank() ? "" : "（@@ " + contextHint + "）";
    }

    private static String previewLines(List<Integer> candidates, int span) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(candidates.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(candidates.get(i) + 1).append('-').append(candidates.get(i) + span);
        }
        if (candidates.size() > limit) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private enum Section {
        NONE,
        ADD,
        UPDATE,
        DELETE,
        UNSUPPORTED
    }

    /** 收束一个段落，把合法的段落转成 {@link PatchOp}。 */
    private static void flush(List<PatchOp> ops, List<String> errors, Section section, String path,
                              List<String> addLines, List<Hunk> hunks, List<String> deleteLines,
                              int sectionStart) {
        if (section == Section.NONE || section == Section.UNSUPPORTED || path == null) {
            return;
        }
        Path target = toPath(path);
        if (target == null) {
            errors.add("第 " + sectionStart + " 行附近：路径 [" + path + "] 非法，该段已忽略。");
            return;
        }
        switch (section) {
            case ADD -> {
                if (addLines.isEmpty()) {
                    errors.add("Add File [" + path + "] 没有任何内容行，该段已忽略。");
                } else {
                    ops.add(new AddFile(target, new ArrayList<>(addLines)));
                }
            }
            case DELETE -> {
                if (!deleteLines.isEmpty()) {
                    errors.add("Delete File [" + path + "] 段内出现了 " + deleteLines.size()
                            + " 行内容；删除文件不需要内容行，这些行已忽略。");
                }
                ops.add(new DeleteFile(target));
            }
            case UPDATE -> {
                if (hunks.isEmpty()) {
                    errors.add("Update File [" + path + "] 没有任何 hunk，该段已忽略。"
                            + "请在段内用 @@ 分隔 hunk，并以空格/-/+ 开头书写内容行。");
                } else {
                    ops.add(new UpdateFile(target, new ArrayList<>(hunks)));
                }
            }
            default -> {
                // NONE / UNSUPPORTED 已在上面返回
            }
        }
    }

    private static String pathOf(String headerLine, String marker, List<String> errors, int lineNumber) {
        String raw = headerLine.substring(marker.length()).strip();
        if (raw.isEmpty()) {
            errors.add("第 " + lineNumber + " 行：" + marker + " 后面缺少路径。");
            return null;
        }
        return unquote(raw);
    }

    private static String unquote(String raw) {
        String value = raw.strip();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path toPath(String raw) {
        try {
            return Path.of(raw);
        } catch (RuntimeException e) {
            // Windows 上的非法字符（如 : * ?）会抛 InvalidPathException
            return null;
        }
    }

    /**
     * 把补丁里的路径解析到 {@code realBase} 之下。
     *
     * <p>两道校验：先 {@code normalize()} 挡住 {@code ..} 与绝对路径，再用"最近存在的祖先目录"的
     * 真实路径挡住符号链接逃逸（{@code root/link -> /etc} 这种情况 normalize 是看不出来的）。</p>
     *
     * @return 绝对且合法的路径；越界返回 {@code null}
     */
    private static Path resolveInside(Path realBase, Path raw) {
        if (raw == null) {
            return null;
        }
        Path candidate = raw.isAbsolute()
                ? raw.normalize()
                : realBase.resolve(raw).normalize();
        if (!candidate.startsWith(realBase)) {
            return null;
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            Path realExisting = realPathOrSelf(existing);
            if (!realExisting.startsWith(realBase)) {
                return null;
            }
        }
        return candidate;
    }

    private static Path realPathOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String show(Path base, Path target) {
        try {
            return base.relativize(target).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return target.toString();
        }
    }

    /** 是否为"裸代码围栏"行（``` 或 ~~~，且不含其它内容）。 */
    private static boolean isFence(String trimmed) {
        if (trimmed.length() < 3) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first != '`' && first != '~') {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 按 {@code \n} 切分并去掉行尾的 {@code \r}，同时丢掉"结尾换行"产生的空元素。 */
    private static List<String> toLines(String content) {
        List<String> lines = new ArrayList<>(Arrays.asList(normalize(content).split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
