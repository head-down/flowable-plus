package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 出域策略注册表（starter <b>包内</b>实现细节，ADR-0030：禁无消费者的公开类型）。
 *
 * <p>与 {@link DecisionTargetRegistry} 同型（ADR-0042 第 6 节：「策略与决策目标同型」——
 * bean + 唯一 {@code key()} + 节点按 key 引用 + 部署期校验）：从
 * {@code @Autowired(required=false) List<DecisionPolicy>} 收集，按 {@link DecisionPolicy#key()}
 * 去重；<b>重复 key ⇒ 启动期 fail-fast</b>。未注册任何策略是<b>合法</b>装配态。</p>
 */
final class DecisionPolicyRegistry {

    /** 去重后的出域策略（按收集序） */
    private final List<DecisionPolicy> policies;

    /** 收口后的 key 集 */
    private final Set<String> keys;

    /**
     * 构造注册表并按 key 去重。
     *
     * @param collected 收集到的出域策略；{@code null} 或空集等价「应用未注册任何策略」（合法）
     * @throws IllegalStateException 存在重复 key 时（启动期 fail-fast）
     */
    DecisionPolicyRegistry(final List<DecisionPolicy> collected) {
        if (collected == null || collected.isEmpty()) {
            this.policies = Collections.emptyList();
            this.keys = Collections.emptySet();
            return;
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final DecisionPolicy policy : collected) {
            Objects.requireNonNull(policy, "出域策略 Bean 不得为 null");
            if (!seen.add(Objects.requireNonNull(policy.key(), "出域策略 key 不得为 null"))) {
                throw new IllegalStateException("出域策略 key 重复（节点按 key 单值引用，不允许二义）：" + policy.key());
            }
        }
        this.policies = Collections.unmodifiableList(new ArrayList<>(collected));
        this.keys = Collections.unmodifiableSet(seen);
    }

    /** 去重后的出域策略（只读，按收集序） */
    List<DecisionPolicy> values() {
        return policies;
    }

    /** 收口后的 key 集（只读） */
    Set<String> keys() {
        return keys;
    }
}
