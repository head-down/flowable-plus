package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 装配器的产出（ADR-0042 第 6 节 / 拉管线决议第九节）：与管线之间传递「<b>空装配</b>这一显式事实」
 * 与「装配出的决策载荷」。
 *
 * <p><b>空装配是显式事实</b>：{@link #emptyAssembly} 由本类型<b>独立承载</b>，管线据此短路
 * （不调策略、不调 provider），<b>不得</b>由「四段全 {@code null}」反推 —— 载荷的 {@code null} 语义是
 * 「该段未声明」，拿它反推空装配会把这个语义毁掉。</p>
 *
 * <p><b>{@link #droppedContextSources} = 装配器丢弃的来源</b>（声明面最小化的<b>基线</b>计数）：
 * 即「有效数据源集之外」的承载单元级来源 —— 它反映<b>建模漏配</b>，与「策略 / clamp 收缩」是两件事，
 * 故<b>不计入任何标志</b>（脱敏 / 截断 / 完整度皆不因它变动），只作可观测计数
 * （见 {@code DecisionObservation.droppedContextSources}）。</p>
 */
@Getter
public final class DecisionAssemblyResult {

    /** 是否为空装配（显式事实，不由「四段全 null」反推） */
    private final boolean emptyAssembly;

    /** 装配出的决策载荷；空装配时为 {@code null} */
    private final DecisionPayload payload;

    /** 装配器丢弃的来源（有效数据源集之外的来源；非标志，只作可观测计数） */
    private final List<DecisionContextSource> droppedContextSources;

    private DecisionAssemblyResult(final boolean emptyAssembly,
                                   final DecisionPayload payload,
                                   final Collection<DecisionContextSource> droppedContextSources) {
        this.emptyAssembly = emptyAssembly;
        this.payload = payload;
        this.droppedContextSources = droppedContextSources == null
                ? Collections.<DecisionContextSource>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(droppedContextSources));
    }

    /**
     * 空装配（零 token ⇒ 短路，不调策略、不调 provider）。
     *
     * @param droppedContextSources 装配器丢弃的来源（未装配的承载单元级来源）
     * @return 空装配结果（{@link #emptyAssembly} = {@code true}、{@link #payload} = {@code null}）
     */
    public static DecisionAssemblyResult empty(final Collection<DecisionContextSource> droppedContextSources) {
        return new DecisionAssemblyResult(true, null, droppedContextSources);
    }

    /**
     * 非空装配结果。
     *
     * @param payload               装配出的决策载荷，不得为 null
     * @param droppedContextSources 装配器丢弃的来源（有效数据源集之外的来源）
     * @return 装配结果（{@link #emptyAssembly} = {@code false}）
     */
    public static DecisionAssemblyResult of(final DecisionPayload payload,
                                            final Collection<DecisionContextSource> droppedContextSources) {
        if (payload == null) {
            throw new IllegalArgumentException("非空装配的载荷不得为 null：空装配请走 empty(...) 这一显式事实");
        }
        return new DecisionAssemblyResult(false, payload, droppedContextSources);
    }
}
