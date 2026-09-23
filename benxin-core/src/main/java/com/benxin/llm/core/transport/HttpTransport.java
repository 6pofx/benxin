package com.benxin.llm.core.transport;

import java.time.Duration;
import java.util.Map;

/**
 * HTTP 传输层 —— "一切可替换"的另一个切面。
 *
 * <p>默认实现基于 JDK 自带的 {@code java.net.http.HttpClient}，零第三方依赖。
 * 想换成 OkHttp / WebClient / 带代理与 mTLS 的自建客户端，实现本接口即可；
 * 单元测试里换成假实现，就能在完全离线的情况下跑通整条模型链路
 * （本心的协议契约测试正是这么做的）。</p>
 */
public interface HttpTransport {

    /** 同步 POST。 */
    TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout);

    /** 流式 POST，返回未消费的输入流。 */
    TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout);
}