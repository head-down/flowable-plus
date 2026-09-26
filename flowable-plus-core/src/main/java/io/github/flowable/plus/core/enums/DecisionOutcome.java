package io.github.flowable.plus.core.enums;

/**
 * 决策证据的顶层结局枚举（ADR-0042 第 5 节）。
 *
 * <p>只取三个叶子态；「未产出」是上位词、仅作文档分类，<b>不承载契约取值</b>，
 * 故不得与本枚举的任一取值并列。</p>
 */
public enum DecisionOutcome {

    /** 已产出建议：拉取机制正常产出建议，并经位点服务提交、留痕 */
    SUGGESTION_PRODUCED,

    /** 按政策未产出：拉取机制按设计工作，结论是有意不产出（护栏拒绝 / 生产者主动不产出） */
    NO_SUGGESTION_BY_POLICY,

    /** 失败：拉取机制未工作的那一类未产出（超时 / 非 2xx / 响应不可解析 / 凭据失效等） */
    SUGGESTION_FAILED
}
