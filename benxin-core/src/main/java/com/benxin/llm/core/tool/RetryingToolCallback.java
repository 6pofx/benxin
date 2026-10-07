package com.benxin.llm.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 可重试的工具装饰器：失败的工具结果按退避策略重试若干次。
 *
 * <p>与 {@code RetryingLlmModel} 分工明确 —— 那个负责模型调用，这个负责工具执行。
 * 两者都由 {@code @LlmRetry} 驱动：</p>
 * <ul>
 *   <li>工具方法上打 {@code @LlmRetry} → {@code ToolScanner} 自动包一层本类；</li>
 *   <li>Agent 接口上写 {@code @LlmRetry(includeTools = true)} → 该 Agent 的全部工具都被包一层。</li>
 * </ul>
 *
 * <p>只重试"结果里 {@code error = true}"的失败与抛出的运行时异常；成功结果原样返回。
 * 重试次数耗尽后返回最后一次结果（含错误），交由上层回灌给模型 —— 重试失败不是异常，
 * 而是"这个工具确实不好使"。</p>
 *
 * <p>确定性失败（参数缺失/参数不合法）不会重试：它们重试多少次都是同一个结果，
 * 白等退避时间还会让交互式 Agent 显得卡住。</p>
 */
public class RetryingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RetryingToolCallback.class);

    /** 单次退避上限：交互式场景下等待比失败更糟（与 {@code RetryingLlmModel} 同口径）。 */
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    /** 重试也没意义的确定性错误前缀（由框架自己产生的那几种）。 */
    private static final String[] DETERMINISTIC_PREFIXES = {"缺少必填参数", "工具参数不合法"};

    private final ToolCallback delegate;
    private final int maxAttempts;
    private final long backoffMillis;
    private final double multiplier;
    private final Predicate<ToolResult> retryOn;

    /**
     * @param delegate      被包装的工具，不能为 null
     * @param maxAttempts   最大尝试次数（<b>含首次</b>），小于 1 按 1 处理
     * @param backoffMillis 首次重试前的等待毫秒数，负数按 0 处理
     * @param multiplier    退避倍数，非法值按 1.0（不退让）处理
     * @param retryOn       额外判定：返回 true 才重试；为 null 时只按 {@link #defaultRetryOn()}
     */
    public RetryingToolCallback(ToolCallback delegate, int maxAttempts, long backoffMillis,
                                double multiplier, Predicate<ToolResult> retryOn) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为 null");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0L, backoffMillis);
        this.multiplier = Double.isFinite(multiplier) && multiplier >= 1 ? multiplier : 1.0d;
        this.retryOn = retryOn == null ? r -> true : retryOn;
    }

    public RetryingToolCallback(ToolCallback delegate, int maxAttempts, long backoffMillis, double multiplier) {
        this(delegate, maxAttempts, backoffMillis, multiplier, null);
    }

    /** 默认的重试判定：失败了、且不是确定性错误。 */
    public static boolean defaultRetryOn(ToolResult result) {
        if (result == null || !result.error()) {
            return false;
        }
        String content = result.content() == null ? "" : result.content();
        for (String prefix : DETERMINISTIC_PREFIXES) {
            if (content.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    public ToolCallback delegate() {
        return delegate;
    }

    @Override
    public com.benxin.llm.core.chat.ToolSpec spec() {
        return delegate.spec();
    }

    /** 审批与并发语义必须透传：包一层重试不该改变"要不要人工审批""能不能并行"。 */
    @Override
    public boolean requiresApproval() {
        return delegate.requiresApproval();
    }

    @Override
    public boolean parallelSafe() {
        return delegate.parallelSafe();
    }

    @Override
    public boolean returnDirect() {
        return delegate.returnDirect();
    }

    @Override
    public ToolResult call(Map<String, Object> arguments, ToolContext context) {
        ToolResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                result = delegate.call(arguments, context);
            } catch (RuntimeException e) {
                // ToolCallback 的约定是"不抛异常"，这里也遵守：转成错误结果再决定要不要重试。
                result = ToolResult.error("工具执行抛出 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (result == null) {
                // 返回 null 的工具是"没结果"，不能把 null 透传给上层（那边会直接 NPE）。
                result = ToolResult.error("工具 [" + name() + "] 未返回结果");
            }
            if (attempt == maxAttempts || !defaultRetryOn(result) || !retryOn.test(result)) {
                return withAttempts(result, attempt);
            }
            long wait = backoff(attempt);
            log.warn("[benxin] 工具 [{}] 第 {} 次执行失败（{}），{}ms 后重试",
                    name(), attempt, result == null ? "无结果" : result.content(), wait);
            if (!sleep(wait)) {
                return withAttempts(result, attempt);
            }
        }
        return withAttempts(result, maxAttempts);
    }

    private long backoff(int attempt) {
        double growth = Math.pow(multiplier, attempt - 1);
        double wait = backoffMillis * (Double.isFinite(growth) ? growth : 1.0d);
        return (long) Math.min(MAX_BACKOFF_MILLIS, Math.max(0d, wait));
    }

    /** @return 是否睡满；被中断时返回 false（并保留中断标记） */
    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 在 meta 里记下尝试次数，便于排查"这个结果其实是重试过的"。 */
    private ToolResult withAttempts(ToolResult result, int attempts) {
        if (result == null || attempts <= 1) {
            return result;
        }
        Map<String, Object> meta = new LinkedHashMap<>(result.meta() == null ? Map.of() : result.meta());
        meta.put("attempts", attempts);
        return new ToolResult(result.content(), result.error(), meta);
    }

    @Override
    public String toString() {
        return "RetryingToolCallback(" + name() + " ×" + maxAttempts + ")";
    }
}
