package io.github.flowable.plus.extension.decision;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策载荷（ADR-0042 第 6 节「载荷同型」）：装配器的产出、策略输入与输出、clamp 输入与输出共用的
 * <b>同一类型</b> —— <b>四段定型外壳</b>，与流程上下文数据同型同构，故「声明了什么段 ⇒ 出去什么段」
 * 可被第三方端到端核对。
 *
 * <p>四段与「有效数据源声明」的四个承载单元级来源一一对应：</p>
 *
 * <ul>
 *   <li>{@link #processVariables} ← 流程变量；</li>
 *   <li>{@link #taskVariables} ← 任务本地变量；</li>
 *   <li>{@link #taskMetadata} ← 任务元数据；</li>
 *   <li>{@link #processInstanceMetadata} ← 流程实例元数据。</li>
 * </ul>
 *
 * <p><b>三态可区分</b>（策略无需猜测）：<b>未声明的段 = {@code null}（缺席）</b>；
 * <b>已声明但该来源为空 = 空集合</b>（变量段）/ 字段全空的小对象（元数据段）。「缺席」与「空」是
 * <b>两态</b>，不得互相折叠 —— 序列化时前者出 {@code null}、后者出 {@code {}}，故本类型
 * <b>不</b>加「忽略空值」一类序列化注解。</p>
 *
 * <p><b>变量段的内容天然未定型</b>（应用自定义命名空间，引擎无从定型），<b>契约面仍是定型的</b>
 * （段是具名、定型的外壳字段）；不为变量段再包一层无意义的类型壳。</p>
 *
 * <p><b>不可变值类型</b>：构造期把两个变量段收为不可修改副本，元数据段为不可变小对象。</p>
 */
@Getter
public final class DecisionPayload {

    /** 流程变量段；{@code null} = 未声明，非 null（含空 map）= 已声明 */
    private final Map<String, Object> processVariables;

    /** 任务本地变量段；{@code null} = 未声明，非 null（含空 map）= 已声明 */
    private final Map<String, Object> taskVariables;

    /** 任务元数据段；{@code null} = 未声明，非 null（字段全空）= 已声明 */
    private final TaskMetadata taskMetadata;

    /** 流程实例元数据段；{@code null} = 未声明，非 null（字段全空）= 已声明 */
    private final ProcessInstanceMetadata processInstanceMetadata;

    /**
     * 构造一段决策载荷。
     *
     * @param processVariables        流程变量段，{@code null} 表示该段未声明
     * @param taskVariables           任务本地变量段，{@code null} 表示该段未声明
     * @param taskMetadata            任务元数据段，{@code null} 表示该段未声明
     * @param processInstanceMetadata 流程实例元数据段，{@code null} 表示该段未声明
     */
    public DecisionPayload(final Map<String, Object> processVariables,
                           final Map<String, Object> taskVariables,
                           final TaskMetadata taskMetadata,
                           final ProcessInstanceMetadata processInstanceMetadata) {
        this.processVariables = immutableCopy(processVariables);
        this.taskVariables = immutableCopy(taskVariables);
        this.taskMetadata = taskMetadata;
        this.processInstanceMetadata = processInstanceMetadata;
    }

    /**
     * 变量段的不可修改收口：{@code null} 原样保留（缺席是契约里的一态，不得折叠成空集合）。
     *
     * @param variables 变量段，可空
     * @return 不可修改副本；入参为 null 时返回 null
     */
    private static Map<String, Object> immutableCopy(final Map<String, Object> variables) {
        return variables == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
