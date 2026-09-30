package com.benxin.llm.core.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 协议注册表 —— "加第四种协议"这条路的落点。
 *
 * <p>它把"有哪些协议可用"从<b>编译期枚举</b>变成<b>运行期注册表</b>：
 * 内置四协议在构造时登记，使用者再注册自己的 {@link ProtocolCodec} 即可让
 * 配置里写 {@code protocol: bedrock} 生效。</p>
 *
 * <h2>两种登记方式</h2>
 *
 * <ul>
 *   <li>{@link #register(ProtocolCodec)} —— 登记一个<b>现成实例</b>。
 *       Spring 路径走这条：bean 实例被直接复用，因此使用者在 Codec 里累加的
 *       计数器、持有的连接、缓存的 token 都是同一份（可观测性依赖这一点）。</li>
 *   <li>{@link #register(String, Supplier)} —— 登记一个<b>工厂</b>。
 *       每次解析都新建实例，适合有状态但要求线程隔离的实现。
 *       内置协议走这条（{@code Codecs} 用反射按需实例化）。</li>
 * </ul>
 *
 * <h2>覆盖规则</h2>
 *
 * <p>后登记的覆盖先登记的，同名以最后一次为准。因此"用自定义 Codec 顶掉内置协议"
 * 也是一行 {@code register} —— 例如想让 {@code protocol: openai} 走自己的报文实现，
 * 直接注册一个 {@code protocol()} 返回 {@link Protocol#OPENAI} 的 Codec 即可。</p>
 *
 * <p>本类线程安全；{@link #find(Protocol)} 返回的是每次解析的产物，
 * 调用方不必自行同步。</p>
 */
public final class ProtocolRegistry {

    /** 一个登记项：要么是现成实例，要么是工厂，二者必居其一。 */
    private static class Entry {
        private final ProtocolCodec singleton;
        private final Supplier<ProtocolCodec> factory;

        private Entry(ProtocolCodec singleton, Supplier<ProtocolCodec> factory) {
            this.singleton = singleton;
            this.factory = factory;
        }

        private ProtocolCodec resolve() {
            return singleton != null ? singleton : factory.get();
        }
    }

    /** 用 LinkedHashMap 保持登记顺序：报错信息里"已注册协议"的顺序要稳定可预期。 */
    private final Map<String, Entry> codecs = new LinkedHashMap<>();

    /** 只装内置四协议的注册表。 */
    public static ProtocolRegistry withBuiltins() {
        ProtocolRegistry registry = new ProtocolRegistry();
        for (Protocol protocol : Protocol.builtins()) {
            registry.register(protocol.id(), () -> Codecs.builtin(protocol)
                    .orElseThrow(() -> new IllegalStateException(
                            "内置协议 [" + protocol.id() + "] 的实现未随 benxin-core 一起发布")));
        }
        return registry;
    }

    /** 空注册表：只认显式登记过的协议，便于测试与"完全自持"的场景。 */
    public static ProtocolRegistry empty() {
        return new ProtocolRegistry();
    }

    /**
     * 登记一个现成的编解码器实例，键取 {@link ProtocolCodec#protocol()}。
     *
     * @return this，便于链式登记
     */
    public synchronized ProtocolRegistry register(ProtocolCodec codec) {
        if (codec == null) {
            return this;
        }
        Protocol protocol = codec.protocol();
        if (protocol == null) {
            // 协议为 null 的 Codec 无法被任何配置引用，静默忽略会让人排查半天
            throw new IllegalArgumentException("ProtocolCodec [" + codec.getClass().getName()
                    + "] 的 protocol() 返回 null，无法登记");
        }
        codecs.put(protocol.id(), new Entry(codec, null));
        return this;
    }

    /**
     * 登记一个编解码器工厂。
     *
     * @param id      协议标识；为 null/空白时忽略
     * @param factory 每次解析时调用的工厂
     * @return this，便于链式登记
     */
    public synchronized ProtocolRegistry register(String id, Supplier<ProtocolCodec> factory) {
        if (id == null || id.isBlank() || factory == null) {
            return this;
        }
        codecs.put(Protocol.of(id).id(), new Entry(null, factory));
        return this;
    }

    /** 按协议解析编解码器；未登记时返回空。 */
    public synchronized Optional<ProtocolCodec> find(Protocol protocol) {
        if (protocol == null) {
            return Optional.empty();
        }
        return find(protocol.id());
    }

    /** 按协议 id 解析编解码器；未登记时返回空。 */
    public synchronized Optional<ProtocolCodec> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Entry entry = codecs.get(Protocol.of(id).id());
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.resolve());
    }

    /** 已登记的协议 id（顺序稳定）。 */
    public synchronized Set<String> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(codecs.keySet()));
    }

    public synchronized boolean isEmpty() {
        return codecs.isEmpty();
    }

    public synchronized int size() {
        return codecs.size();
    }

    /**
     * 复制一份，便于在"内置注册表"基础上叠加而不污染共享实例。
     *
     * <p>注意复制的只是登记项本身：现成实例仍是同一个对象（这正是我们要的 ——
     * 覆盖内置实现时不该把用户 bean 克隆一份）。</p>
     */
    public synchronized ProtocolRegistry copy() {
        ProtocolRegistry copy = new ProtocolRegistry();
        copy.codecs.putAll(this.codecs);
        return copy;
    }

    /** 报错信息里用的可读列表。 */
    public synchronized List<String> describe() {
        return List.copyOf(codecs.keySet());
    }

    @Override
    public synchronized String toString() {
        return "ProtocolRegistry" + codecs.keySet();
    }
}
