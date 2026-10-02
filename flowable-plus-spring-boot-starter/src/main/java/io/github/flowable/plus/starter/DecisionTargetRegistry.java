package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 决策目标注册表（starter <b>包内</b>实现细节，ADR-0030：禁无消费者的公开类型）。
 *
 * <p>从 {@code @Autowired(required=false) List<DecisionTarget>} 收集，按 {@link DecisionTarget#key()}
 * 去重；<b>重复 key ⇒ 启动期 fail-fast</b>（ADR-0042 §7 —— 节点按 key 单值引用，二义引用不成立）。
 * 未注册任何目标（收集为 {@code null} 或空集）是<b>合法</b>装配态：任何引用都被部署期校验阻断，
 * 与 fail-closed 同向。</p>
 *
 * <p>消费者：拉管线（{@code List<DecisionTarget>}）与主闸 / 启动期复核（收口后的 key 集）。</p>
 */
final class DecisionTargetRegistry {

    /** 去重后的决策目标（按收集序） */
    private final List<DecisionTarget> targets;

    /** 收口后的 key 集（构造缝注入主闸与复核的唯一形态） */
    private final Set<String> keys;

    /**
     * 构造注册表并按 key 去重。
     *
     * @param collected 收集到的决策目标；{@code null} 或空集等价「应用未注册任何目标」（合法）
     * @throws IllegalStateException 存在重复 key 时（启动期 fail-fast）
     */
    DecisionTargetRegistry(final List<DecisionTarget> collected) {
        if (collected == null || collected.isEmpty()) {
            this.targets = Collections.emptyList();
            this.keys = Collections.emptySet();
            return;
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final DecisionTarget target : collected) {
            Objects.requireNonNull(target, "决策目标 Bean 不得为 null");
            if (!seen.add(Objects.requireNonNull(target.key(), "决策目标 key 不得为 null"))) {
                throw new IllegalStateException("决策目标 key 重复（节点按 key 单值引用，不允许二义）：" + target.key());
            }
        }
        this.targets = Collections.unmodifiableList(new ArrayList<>(collected));
        this.keys = Collections.unmodifiableSet(seen);
    }

    /** 去重后的决策目标（只读，按收集序） */
    List<DecisionTarget> values() {
        return targets;
    }

    /** 收口后的 key 集（只读） */
    Set<String> keys() {
        return keys;
    }
}
