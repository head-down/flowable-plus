package io.github.flowable.plus.core.enums;

import io.github.flowable.plus.core.vo.DecisionEvidenceTestFixtures;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据行标记的构造来源唯一 + 标记与 {@code TYPE_} 同源（ADR-0042 第 5 节）。
 *
 * <p><b>结构保证</b>：标记前缀 / 后缀在 {@link DecisionEvidenceComment} 内<b>私有</b>，标记由
 * {@link CommentType#DECISION_EVIDENCE} 的 {@code name()} 派生 ⇒ 写侧读侧都无法自行拼装。
 * 本类另以<b>受限源码扫描</b>钉住「全主源码树只有一处拼写标记字面量」。</p>
 *
 * <p><b>标记与 JSON 之间无分隔符</b>：剥离判据 = {@link DecisionEvidenceComment#hasMarker(String)} +
 * {@link DecisionEvidenceComment#stripMarker(String)}（JSON 必以 <code>{</code> 开头，与标记无歧义）。</p>
 */
public class DecisionEvidenceMarkerTest {

    /** 标记字面量的前缀部分 —— 全主源码树（含注释）只允许出现一次 */
    private static final String MARKER_LITERAL_PREFIX = "[SYSTEM:";

    /** 非证据行的人工审批意见 */
    private static final String PLAIN_COMMENT = "同意";

    @Test
    void markerConstructedFromSingleSource() {
        final List<SourceScanSupport.Hit> hits =
                SourceScanSupport.scanMainSources(MARKER_LITERAL_PREFIX);

        assertThat(hits)
                .as("标记字面量只允许有一个声明处")
                .hasSize(1);
        assertThat(hits.stream().map(SourceScanSupport.Hit::getPath).distinct())
                .as("命中文件数 == 1（单一命中点蕴含单一文件，此处对账可读性）")
                .hasSize(1);
        assertThat(hits.get(0).getPath())
                .as("唯一的声明处必须是证据常量类")
                .endsWith("DecisionEvidenceComment.java");
        assertThat(hits.get(0).getLine())
                .as("命中处必须是 MARKER_PREFIX 的声明行")
                .contains("MARKER_PREFIX")
                .contains(MARKER_LITERAL_PREFIX);
    }

    @Test
    void markerIsPrivateAndDerivedFromCommentTypeName() throws Exception {
        final Field prefix = DecisionEvidenceComment.class.getDeclaredField("MARKER_PREFIX");
        final Field suffix = DecisionEvidenceComment.class.getDeclaredField("MARKER_SUFFIX");

        assertThat(ConstantFieldAssertions.isPrivateStaticFinalString(prefix))
                .as("MARKER_PREFIX 必须私有（公开面只留 marker/hasMarker/stripMarker）")
                .isTrue();
        assertThat(ConstantFieldAssertions.isPrivateStaticFinalString(suffix))
                .as("MARKER_SUFFIX 必须私有")
                .isTrue();

        prefix.setAccessible(true);
        suffix.setAccessible(true);
        final String prefixValue = (String) prefix.get(null);
        final String suffixValue = (String) suffix.get(null);

        assertThat(DecisionEvidenceComment.marker())
                .as("标记必须由标记前后缀 + 存库 TYPE_ 取值名派生")
                .isEqualTo(prefixValue + CommentType.DECISION_EVIDENCE.name() + suffixValue);
        assertThat(DecisionEvidenceComment.marker())
                .as("标记与存库 TYPE_ 同源")
                .contains(CommentType.DECISION_EVIDENCE.name());
        assertThat(DecisionEvidenceComment.marker())
                .as("标记不得带分隔符：剥离判据依赖「标记之后紧跟 JSON」")
                .isEqualTo(prefixValue + DecisionEvidenceComment.COMMENT_TYPE.name() + suffixValue);
    }

    @Test
    void stripMarkerRoundTripsWithJsonBody() throws Exception {
        final String jsonBody = DecisionEvidenceTestFixtures.toJson(
                DecisionEvidenceTestFixtures.maximalDirectSubmission());
        assertThat(StringUtils.startsWith(jsonBody, "{")).as("样本必须是 JSON 对象文本").isTrue();
        assertThat(StringUtils.endsWith(jsonBody, "}")).as("样本必须是 JSON 对象文本").isTrue();

        final String fullMessage = DecisionEvidenceComment.marker() + jsonBody;

        assertThat(DecisionEvidenceComment.hasMarker(fullMessage)).isTrue();
        assertThat(DecisionEvidenceComment.stripMarker(fullMessage))
                .as("剥离后必须还原为可解析的 JSON 原文")
                .isEqualTo(jsonBody);
        assertThat(DecisionEvidenceTestFixtures.parse(DecisionEvidenceComment.stripMarker(fullMessage)).isObject())
                .isTrue();

        assertThat(DecisionEvidenceComment.hasMarker(PLAIN_COMMENT)).isFalse();
        assertThat(DecisionEvidenceComment.stripMarker(PLAIN_COMMENT))
                .as("无标记时原样返回")
                .isEqualTo(PLAIN_COMMENT);
        assertThat(DecisionEvidenceComment.hasMarker(null)).isFalse();
        assertThat(DecisionEvidenceComment.stripMarker(null)).isNull();

        final String truncated = StringUtils.chop(DecisionEvidenceComment.marker());
        assertThat(DecisionEvidenceComment.hasMarker(truncated)).isTrue();
        assertThat(DecisionEvidenceComment.stripMarker(truncated))
                .as("标记不完整时原样返回（由读侧容错条款跳过该条投影，此处不抛异常）")
                .isEqualTo(truncated);
    }
}
