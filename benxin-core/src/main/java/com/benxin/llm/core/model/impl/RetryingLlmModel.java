package com.benxin.llm.core.model.impl;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可重试装饰器 —— "一切可替换"最直观的示范。
 *
 * <p>它自己不含任何协议知识：只把调用转发给被包装的 {@link LlmModel}，失败时按指数退避再试。
 * 因此它可以包住任何实现（内置三种协议、用户自定义模型、甚至另一层装饰器），
 * 也可以被任意替换（换成熔断、限流、缓存、录制回放装饰器，上层代码一行不改）。</p>
 *
 * <p>重试判定完全交给 {@link ModelException#isRetryable()}：只重试限流(429)、服务端错误(5xx)
 * 与网络中断这类瞬时故障；参数错误(400)、鉴权失败(401/403)等确定性失败会立刻上抛，
 * 避免把用户的等待时间浪费在必然失败的请求上。</p>
 *
 * <p><b>流式重试的边界</b>：一旦有内容真正下发给调用方，就不可能"撤回"，因此
 * 仅在尚未发出任何内容（未回调 onTextDelta / onThinkingDelta / onToolCall）时才允许重试；
 * 已经出字之后再失败，只能原样把错误转发出去。同理，已经回调过 onComplete 的尝试也不会重试。</p>
 */
public class RetryingLlmModel implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmModel.class);

    /** 便捷构造器的默认重试次数（不含首次调用）。 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 便捷构造器的默认首次退避时长。 */
    public static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 500L;

    /** 便捷构造器的默认退避倍数。 */
    public static final double DEFAULT_MULTIPLIER = 2.0d;

    /**
     * 单次退避上限。
     *
     * <p>取 30 秒的理由：指数退避在大倍数 + 多重试下会迅速膨胀（500ms * 2^10 已超过 8 分钟），
     * 对交互式 Agent 来说等待比失败更糟；封顶后仍保留"越来越慢"的退让语义。</p>
     */
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    private final LlmModel delegate;
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final double multiplier;

    /** 累计已发起的重试次数（不含首次调用），仅用于观测，不参与任何判定。 */
    private final AtomicInteger attempts = new AtomicInteger();

    /**
     * @param delegate             被包装的模型，不能为 null
     * @param maxRetries           最大重试次数（不含首次调用），负数按 0 处理
     * @param initialBackoffMillis 首次重试前的等待时长（毫秒），负数按 0 处理
     * @param multiplier           退避倍数，非法值（负数/NaN/无穷）按 0 处理（即不放大）
     */
    public RetryingLlmModel(LlmModel delegate, int maxRetries, long initialBackoffMillis, double multiplier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为 null");
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoffMillis = Math.max(0L, initialBackoffMillis);
        this.multiplier = Double.isFinite(multiplier) && multiplier > 0 ? multiplier : 0.0d;
    }

    /** 便捷构造器：3 次重试、首次 500ms、倍数 2.0。 */
    public RetryingLlmModel(LlmModel delegate) {
        this(delegate, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF_MILLIS, DEFAULT_MULTIPLIER);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    /**
     * 能力声明直接透传被包装模型。
     *
     * <p>装饰器不改变协议能力（重试不会让模型多出视觉能力），透传才能让 Loop 的降级决策保持正确。</p>
     */
    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return delegate.chat(request);
            } catch (ModelException e) {
                if (!e.isRetryable()) {
                    // 确定性失败（400/401/403 等）：重试没有意义，直接上抛
                    log.debug("[{}] 调用失败且不可重试，直接抛出: {}", name(), e.getMessage());
                    throw e;
                }
                if (attempt >= maxRetries) {
                    log.warn("[{}] 重试次数已用尽（共 {} 次重试），抛出最后一次异常: {}",
                            name(), maxRetries, e.getMessage());
                    throw e;
                }
                int retryNo = attempt + 1;
                long wait = backoffMillis(attempt);
                log.warn("[{}] 第 {} 次调用失败（{}），{}ms 后发起第 {}/{} 次重试",
                        name(), attempt + 1, e.getMessage(), wait, retryNo, maxRetries);
                InterruptedException interrupted = backoff(wait);
                if (interrupted != null) {
                    // 中断意味着上层要求取消：恢复中断标志后立刻放弃重试，绝不吞掉
                    throw new ModelException("模型 [" + name() + "] 第 " + retryNo
                            + " 次重试等待时被中断，已放弃重试。最后一次失败: " + e.getMessage(), interrupted);
                }
                attempts.incrementAndGet();
            }
        }
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("流式回调 handler 不能为 null");
        }
        for (int attempt = 0; ; attempt++) {
            // 每次尝试都换一个新的探针：emitted/error 只对"本次尝试"有意义
            StreamProbe probe = new StreamProbe(handler);
            Throwable failure;
            try {
                delegate.stream(request, probe);
                // 实现方可能只回调 onError 而不抛异常（HttpLlmModel 就是这种风格）
                failure = probe.error();
            } catch (RuntimeException e) {
                // 实现方也可能直接抛（例如传输层在建连阶段就失败）；两种风格都要能处理
                failure = e;
            }
            if (failure == null) {
                return;
            }
            if (probe.emitted() || probe.completed()) {
                // 已经有内容或完整响应到达调用方，重试只会造成重复投递，只能如实报错
                log.warn("[{}] 流式调用失败前已下发{}，不再重试，原样转发错误: {}",
                        name(), probe.emitted() ? "内容" : "完整响应", failure.getMessage());
                handler.onError(failure);
                return;
            }
            if (!isRetryable(failure)) {
                log.debug("[{}] 流式调用失败且不可重试，原样转发错误: {}", name(), failure.getMessage());
                handler.onError(failure);
                return;
            }
            if (attempt >= maxRetries) {
                log.warn("[{}] 流式重试次数已用尽（共 {} 次重试），转发最后一次错误: {}",
                        name(), maxRetries, failure.getMessage());
                handler.onError(failure);
                return;
            }
            int retryNo = attempt + 1;
            long wait = backoffMillis(attempt);
            log.warn("[{}] 第 {} 次流式调用失败（{}），{}ms 后发起第 {}/{} 次重试",
                    name(), attempt + 1, failure.getMessage(), wait, retryNo, maxRetries);
            InterruptedException interrupted = backoff(wait);
            if (interrupted != null) {
                // 流式契约要求错误通过 onError 上报，而不是从 stream() 里抛出去
                handler.onError(new ModelException("模型 [" + name() + "] 第 " + retryNo
                        + " 次重试等待时被中断，已放弃重试。最后一次失败: " + failure.getMessage(), interrupted));
                return;
            }
            attempts.incrementAndGet();
        }
    }

    /** 被包装的模型，便于诊断与再次包装。 */
    public LlmModel delegate() {
        return delegate;
    }

    /**
     * 累计已发起的重试次数（不含首次调用），跨多次 {@code chat}/{@code stream} 调用累加。
     *
     * <p>它只用于观测（日志、指标、测试断言），不影响任何判定逻辑。
     * 需要"总调用次数"时用 {@code attempts() + N}（N 为发起的业务调用数）。</p>
     */
    public int attempts() {
        return attempts.get();
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long initialBackoffMillis() {
        return initialBackoffMillis;
    }

    public double multiplier() {
        return multiplier;
    }

    @Override
    public LlmModel unwrap() {
        return delegate;
    }

    @Override
    public String toString() {
        return "RetryingLlmModel(" + delegate + ", maxRetries=" + maxRetries
                + ", initialBackoff=" + initialBackoffMillis + "ms, multiplier=" + multiplier + ")";
    }

    /**
     * 第 {@code retryIndex} 次重试的退避时长（retryIndex 从 0 开始，因此首次重试的倍数幂为 0）。
     */
    private long backoffMillis(int retryIndex) {
        if (initialBackoffMillis <= 0) {
            return 0L;
        }
        double raw = initialBackoffMillis * Math.pow(multiplier, retryIndex);
        if (Double.isNaN(raw)) {
            return initialBackoffMillis;
        }
        if (raw <= 0) {
            return 0L;
        }
        return (long) Math.min(raw, (double) MAX_BACKOFF_MILLIS);
    }

    /**
     * 退避等待。
     *
     * @return 正常等待结束返回 {@code null}；被中断则<b>恢复中断标志</b>并返回该中断异常，
     *         由调用方决定如何终止（chat 抛出、stream 走 onError）
     */
    private InterruptedException backoff(long millis) {
        if (millis <= 0) {
            return null;
        }
        try {
            Thread.sleep(millis);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    /** 只认 {@link ModelException} 自带的语义；其它异常（编码器 bug、handler 抛错）不盲目重试。 */
    private static boolean isRetryable(Throwable failure) {
        return failure instanceof ModelException modelException && modelException.isRetryable();
    }

    /**
     * 流式回调探针：把被包装模型的回调原样转发给调用方，同时记录"是否已下发内容"与"是否已报错"。
     *
     * <p>两个刻意的设计：</p>
     * <ol>
     *   <li><b>错误先缓冲再决定</b>：{@code onError} 不立即转发。若本次尝试马上要被重试，
     *       提前报错会让调用方先看到一次失败、再看到成功，语义就乱了；只有确定放弃时才转发，
     *       保证"一次 stream() 调用最多一次 onError"。</li>
     *   <li><b>先置 emitted 再转发</b>：万一调用方的回调自己抛异常，emitted 已经为 true，
     *       外层不会把它误判成"可以安全重试"，从而避免重复投递内容。</li>
     * </ol>
     *
     * <p>回调由调用线程同步驱动（{@code SseParser} 就在调用线程上解析），故这里用普通字段；
     * 若将来支持异步回调，需要改为 volatile 或原子类型。</p>
     */
    private static final class StreamProbe implements LlmStreamHandler {

        private final LlmStreamHandler downstream;
        private boolean emitted;
        private boolean completed;
        private Throwable error;

        private StreamProbe(LlmStreamHandler downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onStart() {
            downstream.onStart();
        }

        @Override
        public void onTextDelta(String delta) {
            emitted = true;
            downstream.onTextDelta(delta);
        }

        @Override
        public void onThinkingDelta(String delta) {
            emitted = true;
            downstream.onThinkingDelta(delta);
        }

        @Override
        public void onToolCall(ToolUsePart toolUse) {
            emitted = true;
            downstream.onToolCall(toolUse);
        }

        /**
         * 用量照实透传，不做缓冲。
         *
         * <p>失败的尝试确实消耗了上游 token（至少是输入），把它计入观测比丢弃更接近事实；
         * 调用方若只想统计成功轮次的用量，可用 {@code RetryingLlmModel.attempts()} 识别重试。</p>
         */
        @Override
        public void onUsage(Usage usage) {
            downstream.onUsage(usage);
        }

        @Override
        public void onComplete(ChatResponse response) {
            // 先置位再转发：完整响应一旦交付就无法撤回，后续错误不允许触发重试
            completed = true;
            downstream.onComplete(response);
        }

        @Override
        public void onError(Throwable cause) {
            // 只保留首个错误：后续错误通常是首个错误的连锁反应（例如解码器 error + 流读取失败）
            if (error == null) {
                error = cause;
            }
        }

        private boolean emitted() {
            return emitted;
        }

        private boolean completed() {
            return completed;
        }

        private Throwable error() {
            return error;
        }
    }
}
