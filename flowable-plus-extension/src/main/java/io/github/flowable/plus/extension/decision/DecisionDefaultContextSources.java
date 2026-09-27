package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 应用级默认数据源集（ADR-0042 第 6 节「有效数据源声明」的应用级一侧）：节点<b>缺席</b>声明时，
 * 装配器下取的那一份数据源集。
 *
 * <p><b>形态 = 定型值类型（安全即代码）</b>，不是扁平配置属性 —— 数据源声明是<b>安全边界</b>，
 * 用配置面表达会把它降级成「一个可被误配的字符串」。应用以<b>单一可选 Bean</b> 提供；框架不注册。</p>
 *
 * <p><b>缺失或空集一律按空集处理</b>：缺省即 {@link #empty()}，框架代码中<b>不得存在任何隐式全集
 * 兜底</b> —— 「没配」不等于「全都要」。</p>
 */
public final class DecisionDefaultContextSources {

    /** 内在集合（不可修改；空集是合法的一态） */
    private final Set<DecisionContextSource> sources;

    /**
     * 构造应用级默认数据源集。
     *
     * @param sources 默认数据源集；{@code null} 与空集同义（皆按空集）
     */
    public DecisionDefaultContextSources(final Set<DecisionContextSource> sources) {
        this.sources = sources == null
                ? Collections.<DecisionContextSource>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(sources));
    }

    /**
     * 空的应用级默认数据源集（缺失 / 未配的等价形态）。
     *
     * @return 空集
     */
    public static DecisionDefaultContextSources empty() {
        return new DecisionDefaultContextSources(null);
    }

    /**
     * 默认数据源集（只读）。
     *
     * @return 不可修改集合；可能为空集
     */
    public Set<DecisionContextSource> getSources() {
        return sources;
    }
}
