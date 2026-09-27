package io.github.flowable.plus.extension.decision;

import lombok.Getter;

import java.util.Map;

/**
 * 装配器的一致性快照（ADR-0042 第 6 节 / 拉管线决议第九节）：<b>单次引擎命令内</b>读到的四份原始上下文。
 *
 * <p>本类型只承载「一次读」的产物，<b>不</b>决定装配面 —— 哪些段进入载荷由装配器按<b>有效数据源声明</b>
 * 决定。段的内容一律<b>原样</b>（变量段取引擎给出的 map、元数据段取定型小对象），装配器不做字段级取舍
 * （字段取舍归出域策略的内容选择）。</p>
 *
 * <p><b>实现细节，不入机制术语表</b>（命名宪章 §4.5）。</p>
 */
@Getter
final class DecisionContextSnapshot {

    /** 流程变量（根执行作用域） */
    private final Map<String, Object> processVariables;

    /** 任务本地变量 */
    private final Map<String, Object> taskVariables;

    /** 任务元数据 */
    private final TaskMetadata taskMetadata;

    /** 流程实例元数据 */
    private final ProcessInstanceMetadata processInstanceMetadata;

    /**
     * 构造一致性快照。
     *
     * @param processVariables        流程变量
     * @param taskVariables           任务本地变量
     * @param taskMetadata            任务元数据
     * @param processInstanceMetadata 流程实例元数据
     */
    DecisionContextSnapshot(final Map<String, Object> processVariables,
                            final Map<String, Object> taskVariables,
                            final TaskMetadata taskMetadata,
                            final ProcessInstanceMetadata processInstanceMetadata) {
        this.processVariables = processVariables;
        this.taskVariables = taskVariables;
        this.taskMetadata = taskMetadata;
        this.processInstanceMetadata = processInstanceMetadata;
    }
}
