package io.github.flowable.plus.core.workflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionEvidenceReadGuard;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.UnorderedDecisionEvidences;
import org.flowable.engine.task.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 决策证据行的<b>读侧投影器</b>（ADR-0042 第 5 节读侧硬清单第 7 项）：把 {@code ACT_HI_COMMENT}
 * 里的证据行（{@code TYPE_ = DECISION_EVIDENCE}）还原为 {@link DecisionEvidenceVO}。
 *
 * <p><b>链路</b>：按 {@code TYPE_} 挑行 → {@link DecisionEvidenceComment#stripMarker} 剥标记 →
 * 解析护栏 → Jackson 反序列化 → 按 {@code TIME_} 升序（同毫秒按数值 {@code ID_} 升序）重排。</p>
 *
 * <p><b>容错是字段级的</b>（ADR-0042 第 5 节容错条款）：<b>判别式</b>（{@code outcome}）不可解析、
 * 或载荷损坏（有标记但 JSON 不可解析）⇒ <b>只跳过该条证据投影</b>，调用方的审批轨迹行不受影响；
 * <b>次要枚举</b>取值未知（{@code policyReason} / {@code failureKind} / {@code chainStage} /
 * {@code subjectType} / {@code completeness}）⇒ 只做<b>字段级降级 {@code null}</b>，该条证据仍投影；
 * {@code schemaVersion} 高于本读侧已知最大值 ⇒ <b>尽力读、不做版本门禁</b>。</p>
 *
 * <p><b>读侧护栏先于解析</b>（G7）：先按 UTF-8 字节数比对
 * {@link DecisionEvidenceReadGuard#MAX_PARSE_BYTES}，再以流式词法扫描量出嵌套深度比对
 * {@link DecisionEvidenceReadGuard#MAX_NESTING_DEPTH}，<b>两者都在整树解析之前</b>。超限
 * ⇒ 只跳过该条证据投影、审批轨迹行本身不消失（G6）。</p>
 *
 * <p><b>重放判序</b>：本类只负责把行排成「锚点内 {@code TIME_} 升序」—— 即
 * {@link io.github.flowable.plus.core.vo.ApprovalRecordVO#resolveReplayOf} 认定的「最早 = 原」
 * 所指的次序。同毫秒并列时按数值 {@code ID_} 升序兜底（引擎 {@code getProcessInstanceComments}
 * 实为 {@code TIME_ desc} 且无次级排序键，见 {@code docs/known-drifts.md} 的 D4）。
 * <b>不可判 ⇒ 整锚点不判（ADR-0042 第 9 节第 7 条）</b>：任一 {@code ID_} 非数值（应用替换了
 * {@code IdGenerator}）时，本类<b>不建立任何次序</b>（含跨毫秒的 {@code TIME_} 重排——判定依赖的
 * 「最早在前」契约整体不可信）、证据行<b>照常投影</b>（不可判 ≠ 损坏、≠ 抑制），并以
 * {@link UnorderedDecisionEvidences} 交出——「拒绝标注」的机械承载位：判定面识别该类型即整锚点
 * 返回 {@code null}。同时落一条 {@code WARN}（「落日志与指标」中 core 侧可承载的一半；指标面无
 * 本机制信号，见探索工作区落点文件的 E12 登记块）。</p>
 */
final class DecisionEvidenceRowProjector {

    private static final Logger log = LoggerFactory.getLogger(DecisionEvidenceRowProjector.class);

    /** 证据载荷的判别式字段名（JSON 键 = VO 字段名） */
    private static final String OUTCOME_KEY = "outcome";

    /**
     * 证据行专用 JSON 入口。核心模块单测与运行期均无容器实例可注入，故按规范在此自行构造一次；
     * 构造后<b>不再改配置</b>（配置完成的 {@code ObjectMapper} 对读写线程安全）。
     *
     * <p>两项配置都是读侧容错条款的直接落实：未知属性不报错（{@code schemaVersion} 更高时尽力读）、
     * 未知枚举取 {@code null}（次要枚举字段级降级）。判别式 {@code outcome} 不依赖该配置 ——
     * 它由本类显式先行判定，以免「未知取值 ⇒ 降级 null」把整条证据的取舍权也一并吞掉。</p>
     */
    private static final ObjectMapper EVIDENCE_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    private DecisionEvidenceRowProjector() {
    }

    /**
     * 把一条任务（锚点）下的全部评论投影为决策证据组。
     *
     * @param taskComments 该任务的评论（任意序，本方法自行重排）
     * @return 证据组，按「最早在前」排列；锚点不可判时以 {@link UnorderedDecisionEvidences} 交出
     *         （内容完整、序未建立）；无证据行或全部不可投影时返回空集合（永不为 null）
     */
    static List<DecisionEvidenceVO> project(List<Comment> taskComments) {
        List<Comment> evidenceRows = evidenceRows(taskComments);
        if (evidenceRows.isEmpty()) {
            return new ArrayList<>();
        }
        if (!anchorOrderDecidable(evidenceRows)) {
            // 不可判 ⇒ 整锚点不建序、不判原 / 重放；证据行照常投影（不可判 ≠ 损坏、≠ 抑制）
            log.warn("证据行 ID_ 非数值，该锚点不建立次序、整锚点不判原 / 重放（拒绝标注）：行数={}",
                    evidenceRows.size());
            return projectUnordered(evidenceRows);
        }
        orderAnchorAscending(evidenceRows);
        return evidenceRows.stream()
                .map(DecisionEvidenceRowProjector::readEvidence)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 锚点次序是否可判：全部 {@code ID_} 可解析为数值 ⇒ 可判（{@code TIME_} 升序 + 同毫秒数值
     * {@code ID_} 兜底构成确定的全序）；任一不可解析 ⇒ 整锚点不可判（ADR-0042 第 9 节第 7 条）。
     */
    private static boolean anchorOrderDecidable(List<Comment> rows) {
        return rows.stream().allMatch(row -> numericId(row.getId()) != null);
    }

    /** 未建序投影：不重排（保持引擎原样次序），逐行还原、跳过不可投影行。 */
    private static List<DecisionEvidenceVO> projectUnordered(List<Comment> evidenceRows) {
        return evidenceRows.stream()
                .map(DecisionEvidenceRowProjector::readEvidence)
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toList(),
                        UnorderedDecisionEvidences::of));
    }

    /**
     * 按 {@code TYPE_} 挑出证据行 —— 「是否本机制的证据行」由 {@code TYPE_} 承担，
     * 标记只承担「从哪里切分标记与 JSON」。
     */
    private static List<Comment> evidenceRows(List<Comment> taskComments) {
        if (taskComments == null) {
            return new ArrayList<>();
        }
        String evidenceType = DecisionEvidenceComment.COMMENT_TYPE.name();
        return taskComments.stream()
                .filter(comment -> evidenceType.equals(comment.getType()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 锚点内按 {@code TIME_} 升序重排；同毫秒按数值 {@code ID_} 升序兜底。
     * 仅在锚点次序可判（全部 {@code ID_} 数值化）时调用。
     */
    private static void orderAnchorAscending(List<Comment> rows) {
        rows.sort(Comparator
                .comparing(Comment::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DecisionEvidenceRowProjector::sameMillisecondOrder));
    }

    private static int sameMillisecondOrder(Comment left, Comment right) {
        Long leftId = numericId(left.getId());
        Long rightId = numericId(right.getId());
        if (leftId == null || rightId == null) {
            return 0;
        }
        return Long.compare(leftId, rightId);
    }

    /**
     * 取 {@code ID_} 的数值形态；不可解析（应用替换了 {@code IdGenerator}）时返回 {@code null}。
     *
     * <p><b>实现期订正（2026-09-27，验证收口票的真引擎测试披露）</b>：原实现调
     * {@code NumberUtils.createLong} 并在注释里声称「解析失败返回 {@code null}」—— 该说法有误，
     * {@code createLong} 失败时<b>抛 {@code NumberFormatException}</b>，恰好击穿「不可解析 ⇒ 不建立
     * 次序」的判据（默认 {@code DbIdGenerator} 下 ID_ 恒数值，缺陷从未显形）。改为显式 try / catch：
     * 失败返回 {@code null}，判据本身不变。</p>
     */
    private static Long numericId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException unparseableId) {
            return null;
        }
    }

    /**
     * 单条证据行的还原：剥标记 → 护栏 → 解析 → 判别式校验。
     *
     * @return 可投影的证据；判别式不可解析 / 载荷损坏 / 超护栏时返回 {@code null}（只跳过该条）
     */
    private static DecisionEvidenceVO readEvidence(Comment row) {
        String json = DecisionEvidenceComment.stripMarker(row.getFullMessage());
        if (json == null) {
            return null;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DecisionEvidenceReadGuard.MAX_PARSE_BYTES) {
            log.warn("证据行超出读侧解析大小上限，跳过该条证据投影：上限={}, 实际={} 字节",
                    DecisionEvidenceReadGuard.MAX_PARSE_BYTES, bytes);
            return null;
        }
        try {
            if (maxNestingDepth(json) > DecisionEvidenceReadGuard.MAX_NESTING_DEPTH) {
                log.warn("证据行超出读侧嵌套深度上限，跳过该条证据投影：上限={}",
                        DecisionEvidenceReadGuard.MAX_NESTING_DEPTH);
                return null;
            }
            JsonNode root = EVIDENCE_MAPPER.readTree(json);
            if (root == null || !root.isObject() || !isKnownOutcome(root)) {
                log.warn("证据行判别式不可解析，跳过该条证据投影");
                return null;
            }
            return EVIDENCE_MAPPER.treeToValue(root, DecisionEvidenceVO.class);
        } catch (IOException | RuntimeException broken) {
            // 宽捕的违规原因（规范要求注明）：证据载荷来自外部、属不可信输入，「载荷损坏 ⇒ 只跳过该条
            // 证据投影、审批轨迹行不消失」是 ADR-0042 第 5 节的硬性容错条款，故解析期的任何异常
            // （IO 层解析错、绑定期的类型错）都不允许逃逸到调用方 —— 逐类型捕获会漏掉后者。
            log.warn("证据行载荷损坏，跳过该条证据投影", broken);
            return null;
        }
    }

    /** 判别式只有取三个叶子态之一才可投影；未知取值不做字段级降级，整条跳过。 */
    private static boolean isKnownOutcome(JsonNode root) {
        JsonNode outcomeNode = root.get(OUTCOME_KEY);
        if (outcomeNode == null || !outcomeNode.isTextual()) {
            return false;
        }
        try {
            DecisionOutcome.valueOf(outcomeNode.asText());
            return true;
        } catch (IllegalArgumentException unknownOutcome) {
            return false;
        }
    }

    /**
     * 以流式词法扫描量出 JSON 文本的最大容器嵌套深度。
     *
     * <p>借 Jackson 的词法层计数，不自行扫描括号 —— 字符串与转义由解析器负责；且是<b>单遍流式</b>扫描，
     * 不物化整棵语法树，故可在整树解析之前生效。</p>
     */
    private static int maxNestingDepth(String json) throws IOException {
        int depth = 0;
        int max = 0;
        try (JsonParser parser = EVIDENCE_MAPPER.getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    max = Math.max(max, depth);
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
            }
        }
        return max;
    }
}
