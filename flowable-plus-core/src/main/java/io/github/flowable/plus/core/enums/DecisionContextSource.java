package io.github.flowable.plus.core.enums;

/**
 * 有效数据源声明（{@code decisionDataSources} 的 token 闭集）。
 *
 * <p><b>住所 = core</b> —— 由 {@code DecisionEvidenceVO.attestedDataSources} 的元素类型结构性逼出
 * （core 不得依赖 extension，故该闭集不能在 extension 侧定义）。</p>
 *
 * <p>成员为<b>承载单元级</b>（不在可枚举的元数据上开字段级枚举）；BPMN token = <b>枚举常量名原文</b>
 * （大写下划线、大小写敏感、仅两侧空白容忍），不引入映射表。</p>
 *
 * <p>{@code dropPriority} 为出域 clamp 的整段丢弃序：<b>数值越大越先丢</b>、留 10 间隔、互异且非零；
 * 顺序是<b>显式契约</b>，不依赖物理声明序。</p>
 */
public enum DecisionContextSource {

    /** 流程变量 */
    PROCESS_VARIABLES(40),

    /** 任务本地变量 */
    TASK_VARIABLES(30),

    /** 任务元数据 */
    TASK_METADATA(20),

    /** 流程实例元数据 */
    PROCESS_INSTANCE_METADATA(10);

    private final int dropPriority;

    DecisionContextSource(final int dropPriority) {
        this.dropPriority = dropPriority;
    }

    /**
     * 整段丢弃序：数值越大越先丢。
     *
     * @return 丢弃优先级
     */
    public int getDropPriority() {
        return dropPriority;
    }
}
