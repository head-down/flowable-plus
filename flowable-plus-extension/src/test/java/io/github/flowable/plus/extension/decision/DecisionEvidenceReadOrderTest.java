package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.model.CountersignRoundResolver;
import io.github.flowable.plus.core.model.DefaultBpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.support.DefaultActionInferenceStrategy;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.UnorderedDecisionEvidences;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.TaskService;
import org.flowable.engine.task.Comment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E12 —— 读侧顺序与重放判定输入的构造（真引擎；探索工作区落点文件 §3.2 的 {@code E12}，靶子⑤主落点，I3）。
 *
 * <p><b>七条具名断言</b>（逐字）：锚点内 {@code TIME_} 升序重排 · 同毫秒按数值 {@code ID_} 升序
 * tie-break · {@code ID_} 不可解析 ⇒ 整锚点不判 · 不可判时无任何重放标注 · {@code DbIdGenerator}
 * 单调假设守卫 · 假设击穿降级路径 · 不假定引擎升序。</p>
 *
 * <p><b>排序依据是引擎实测事实、不是注释</b>：{@code HistoryWorkflow} 源码注释记「按时间升序」，
 * 引擎实为 {@code TIME_ desc} 且<b>无次级排序键</b>（6.8.0 内排序全在 MyBatis SQL；{@code ACT_HI_COMMENT}
 * 无 SEQ / 自增列；{@code ID_} 由 {@code DbIdGenerator} 生成、数值序全局单调但字典序不可排序）——
 * 见 {@code docs/known-drifts.md} 的 <b>D4</b>（2026-09-25 源码核实）。本类 {@code #doesNotAssumeEngineAscendingOrder}
 * 与 {@code #readsReSortToTimeAscendingWithinAnchor} 用真引擎把这一漂移钉成可判事实。</p>
 *
 * <p><b>「不可判 ⇒ 整锚点不判」的载体（实现期裁定，已登记）</b>：证据 VO 不携带时序 / 序号元数据，
 * 重放判定面无法从列表内容识别次序可信度；「未建序」由列表的运行时类型
 * {@link UnorderedDecisionEvidences} 承载 —— 读侧投影器在锚点不可判时以它交出（内容完整、
 * 照记不抑制），判定面对它一律拒绝标注。本类的后三条断言把该载体钉成可判事实。</p>
 *
 * <p><b>fixture 构造的边界（如实披露）</b>：证据行经引擎公开位点
 * {@code TaskService#addComment(taskId, processInstanceId, type, message)} 写入（与框架写入器同一调用形态）；
 * 「同一毫秒 / 指定先后」的行状态由引擎自身无公开位点可构造（评论时间由引擎取当前时刻），故经 JDBC 直连
 * 内存库改写 {@code ACT_HI_COMMENT.TIME_} —— 这是 <b>fixture 构造</b>（模拟「两行落在同一毫秒」这一
 * tie-break 的适用场景），不是对抗路径：对抗动作（同一幂等身份重复到达）走的是引擎公开位点。</p>
 */
class DecisionEvidenceReadOrderTest {

    private static final String PROCESS_KEY = "decisionEvidenceReadOrderProcess";

    private static final String ANCHOR_NODE_ID = "anchorTask";

    private static final String RESOURCE_NAME = "decision-evidence-read-order.bpmn20.xml";

    /** 发起人 / 锚点任务办理人（UserContext 的确定值） */
    private static final String USER_ID = "readorder-user";

    /** 幂等键的行间区分前缀（每条自定，不复制任何框架值） */
    private static final String KEY_PREFIX = "idem-read-order-";

    /** 默认引擎（真 ID_ 序列；固定 H2，时间改写走 {@code ExtensionTestEngine#jdbcUrl}） */
    private static ProcessEngine defaultEngine;

    /** 换用 IdGenerator 的引擎（{@code ID_} 不可解析 —— ADR 第 9 节第 7 条预设的应用侧替换场景） */
    private static ProcessEngine nonNumericIdEngine;

    private static final AtomicLong NON_NUMERIC_ID_SEQUENCE = new AtomicLong(1);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void startEngines() {
        defaultEngine = ExtensionTestEngine.build();
        nonNumericIdEngine = ExtensionTestEngine.buildIsolated(null,
                () -> "gen-" + NON_NUMERIC_ID_SEQUENCE.getAndIncrement());
        deployBpmn(defaultEngine);
        deployBpmn(nonNumericIdEngine);
    }

    @AfterAll
    static void stopEngines() {
        defaultEngine.close();
        nonNumericIdEngine.close();
    }

    @Test
    @DisplayName("锚点内按 TIME_ 升序重排（引擎实为降序，读侧自行重排）")
    void readsReSortToTimeAscendingWithinAnchor() throws Exception {
        Anchor anchor = activeAnchor(defaultEngine);
        String key = KEY_PREFIX + "resort";
        // 先写 A、后写 B（ID_A < ID_B），但把 A 的时间调到更晚 ⇒ 「最早」= B（后写者）
        String rowA = writeEvidenceRow(defaultEngine, anchor, key, "行A");
        String rowB = writeEvidenceRow(defaultEngine, anchor, key, "行B");
        completeAnchor(defaultEngine, anchor);
        rewriteTimes(defaultEngine, rowA, 2_000L, rowB, 1_000L);

        List<Comment> raw = rawEngineComments(defaultEngine, anchor.processInstanceId);
        assertThat(times(raw)).as("引擎返回实为 TIME_ 降序（D4 实测）").containsExactly(
                new Date(2_000L), new Date(1_000L));

        ApprovalRecordVO record = anchorRecord(defaultEngine, anchor);
        List<DecisionEvidenceVO> evidences = record.getDecisionEvidences();
        assertThat(evidences).hasSize(2);
        // 读侧重排后：B（TIME_ 最早）在前 —— 「最早 = 原」由重排后的列表序建立
        assertThat(record.resolveReplayOf(evidences.get(0))).isNull();
        assertThat(record.resolveReplayOf(evidences.get(1))).isEqualTo(evidences.get(0));
    }

    @Test
    @DisplayName("不假定引擎升序：引擎先返回的行不是「原行」，时间最早者才是")
    void doesNotAssumeEngineAscendingOrder() throws Exception {
        Anchor anchor = activeAnchor(defaultEngine);
        String key = KEY_PREFIX + "assumption";
        String rowA = writeEvidenceRow(defaultEngine, anchor, key, "行A");
        String rowB = writeEvidenceRow(defaultEngine, anchor, key, "行B");
        completeAnchor(defaultEngine, anchor);
        rewriteTimes(defaultEngine, rowA, 2_000L, rowB, 1_000L);

        List<Comment> raw = rawEngineComments(defaultEngine, anchor.processInstanceId);
        // 引擎原样序 = [A(晚), B(早)]：若照 HistoryWorkflow 注释假定升序，「原行」会取 A —— 错。
        assertThat(raw.get(0).getId()).as("引擎先返回的是时间更晚的行").isEqualTo(rowA);

        ApprovalRecordVO record = anchorRecord(defaultEngine, anchor);
        List<DecisionEvidenceVO> evidences = record.getDecisionEvidences();
        DecisionEvidenceVO projectedA = evidences.get(1);
        DecisionEvidenceVO projectedB = evidences.get(0);
        // 引擎先返回的 A 被判为 B 的重放；时间最早的 B 是原行 —— 读侧没有照注释假定升序。
        assertThat(record.resolveReplayOf(projectedA)).isEqualTo(projectedB);
        assertThat(record.resolveReplayOf(projectedB)).isNull();
    }

    @Test
    @DisplayName("同毫秒并列按数值 ID_ 升序 tie-break（写入序）")
    void sameMillisecondBreaksTieByNumericId() throws Exception {
        Anchor anchor = activeAnchor(defaultEngine);
        String key = KEY_PREFIX + "tie";
        String rowA = writeEvidenceRow(defaultEngine, anchor, key, "行A");
        String rowB = writeEvidenceRow(defaultEngine, anchor, key, "行B");
        // 两行落同一毫秒：同毫秒本无客观先后，确定性约定 = 数值 ID_ 升序（= 写入序）
        completeAnchor(defaultEngine, anchor);
        rewriteTimes(defaultEngine, rowA, 5_000L, rowB, 5_000L);

        List<Comment> raw = rawEngineComments(defaultEngine, anchor.processInstanceId);
        assertThat(times(raw)).as("fixture 生效：两行同一毫秒")
                .containsExactly(new Date(5_000L), new Date(5_000L));
        assertThat(idsAscending(raw))
                .as("fixture 守卫：默认 DbIdGenerator 下 ID_ 数值升序 = 写入序（A 先写）")
                .isTrue();

        ApprovalRecordVO record = anchorRecord(defaultEngine, anchor);
        List<DecisionEvidenceVO> evidences = record.getDecisionEvidences();
        assertThat(evidences).hasSize(2);
        // 列表序 = 数值 ID_ 升序：A（先写）在前 = 原行；B 判为 A 的重放
        assertThat(record.resolveReplayOf(evidences.get(0))).isNull();
        assertThat(record.resolveReplayOf(evidences.get(1))).isEqualTo(evidences.get(0));
    }

    @Test
    @DisplayName("默认 DbIdGenerator 下后写 ⇒ 数值 ID_ 更大（tie-break 依赖的单调假设守卫）")
    void dbIdGeneratorMonotonicAssumptionHolds() throws Exception {
        Anchor anchor = activeAnchor(defaultEngine);
        String rowA = writeEvidenceRow(defaultEngine, anchor, KEY_PREFIX + "mono-a", "行A");
        String rowB = writeEvidenceRow(defaultEngine, anchor, KEY_PREFIX + "mono-b", "行B");
        completeAnchor(defaultEngine, anchor);

        List<Comment> raw = rawEngineComments(defaultEngine, anchor.processInstanceId);
        assertThat(raw).hasSize(2);
        Long idA = numericId(rowA);
        Long idB = numericId(rowB);
        assertThat(idA).as("默认引擎的 ID_ 必须可解析为数值（单调假设的前提）").isNotNull();
        assertThat(idB).isNotNull();
        assertThat(idB).as("后写的行数值 ID_ 更大（假设成立 ⇒ tie-break 数值升序 = 写入序）")
                .isGreaterThan(idA);
    }

    @Test
    @DisplayName("ID_ 不可解析 ⇒ 整锚点不判：证据行照记（不可判 ≠ 抑制）、以未建序载体交出")
    void nonNumericIdMakesWholeAnchorUndecidable() {
        Anchor anchor = activeAnchor(nonNumericIdEngine);
        String key = KEY_PREFIX + "undecidable";
        writeEvidenceRow(nonNumericIdEngine, anchor, key, "行一");
        writeEvidenceRow(nonNumericIdEngine, anchor, key, "行二");
        completeAnchor(nonNumericIdEngine, anchor);

        ApprovalRecordVO record = anchorRecord(nonNumericIdEngine, anchor);
        List<DecisionEvidenceVO> evidences = record.getDecisionEvidences();
        // 照记不抑制：不可判 ≠ 损坏、≠ 抑制，两行都在
        assertThat(evidences).as("不可判锚点的证据行照常投影").hasSize(2);
        // 「未建序」由列表运行时类型承载（实现期裁定的机械载体）
        assertThat(evidences).as("锚点序未建立 ⇒ 未建序载体").isInstanceOf(UnorderedDecisionEvidences.class);
        // 整锚点不判：任一 ID_ 非数值 ⇒ 该锚点整体不判原 / 重放
        assertThat(record.resolveReplayOf(evidences.get(0))).isNull();
        assertThat(record.resolveReplayOf(evidences.get(1))).isNull();
    }

    @Test
    @DisplayName("不可判时无任何重放标注：逐行拒绝")
    void noReplayMarkingWhenUndecidable() {
        Anchor anchor = activeAnchor(nonNumericIdEngine);
        String key = KEY_PREFIX + "nomarking";
        writeEvidenceRow(nonNumericIdEngine, anchor, key, "行一");
        writeEvidenceRow(nonNumericIdEngine, anchor, key, "行二");
        writeEvidenceRow(nonNumericIdEngine, anchor, key, "行三");
        completeAnchor(nonNumericIdEngine, anchor);

        ApprovalRecordVO record = anchorRecord(nonNumericIdEngine, anchor);
        List<DecisionEvidenceVO> evidences = record.getDecisionEvidences();
        assertThat(evidences).hasSize(3);
        for (final DecisionEvidenceVO evidence : evidences) {
            assertThat(record.resolveReplayOf(evidence))
                    .as("不可判锚点内不得出现任何重放标注（拒绝标注优于标注错）")
                    .isNull();
        }
    }

    @Test
    @DisplayName("假设击穿 ⇒ 降级路径触发：同键重复到达不再被判为重放（对照可判锚点的行为）")
    void degradesWhenAssumptionIsBroken() {
        // 破坏假设的引擎：同键两行、时间天然可分也不行 —— 判定依赖的「最早在前」契约整体不可信
        Anchor broken = activeAnchor(nonNumericIdEngine);
        String key = KEY_PREFIX + "degrade";
        writeEvidenceRow(nonNumericIdEngine, broken, key, "行A");
        writeEvidenceRow(nonNumericIdEngine, broken, key, "行A");
        completeAnchor(nonNumericIdEngine, broken);
        ApprovalRecordVO brokenRecord = anchorRecord(nonNumericIdEngine, broken);
        assertThat(brokenRecord.getDecisionEvidences())
                .as("降级不是丢弃：两行照记")
                .hasSize(2);
        for (final DecisionEvidenceVO evidence : brokenRecord.getDecisionEvidences()) {
            assertThat(brokenRecord.resolveReplayOf(evidence))
                    .as("假设击穿 ⇒ 降级为不判（对照：可判锚点同形态会标注，见 #readsReSortToTimeAscendingWithinAnchor）")
                    .isNull();
        }
    }

    // ======================== fixture 与读取辅助 ========================

    /** 锚点现场：流程实例 + 已办结的锚点任务（证据行挂它） */
    private static final class Anchor {
        final String processInstanceId;
        final String taskId;

        Anchor(String processInstanceId, String taskId) {
            this.processInstanceId = processInstanceId;
            this.taskId = taskId;
        }
    }

    private static void deployBpmn(ProcessEngine engine) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:flowable=\"http://flowable.org/bpmn\""
                + " targetNamespace=\"http://flowable.plus/extension/test\">"
                + "<process id=\"" + PROCESS_KEY + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"to-anchor\" sourceRef=\"start\" targetRef=\"" + ANCHOR_NODE_ID + "\"/>"
                + "<userTask id=\"" + ANCHOR_NODE_ID + "\" name=\"锚点\" flowable:assignee=\"" + USER_ID + "\"/>"
                + "<sequenceFlow id=\"to-end\" sourceRef=\"" + ANCHOR_NODE_ID + "\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/>"
                + "</process>"
                + "</definitions>";
        engine.getRepositoryService().createDeployment().addString(RESOURCE_NAME, xml).deploy();
    }

    /** 发起流程，返回锚点任务<b>在办</b>的现场（证据行须在任务存续期写入 —— 引擎的评论位点解析运行时任务）。 */
    private static Anchor activeAnchor(ProcessEngine engine) {
        String processInstanceId = engine.getRuntimeService()
                .startProcessInstanceByKey(PROCESS_KEY, "biz-" + System.nanoTime()).getId();
        String taskId = singleActiveTaskId(engine, processInstanceId);
        return new Anchor(processInstanceId, taskId);
    }

    /** 办结锚点任务（证据行写入之后调用；评论行独立于任务存续，落在历史表）。 */
    private static void completeAnchor(ProcessEngine engine, Anchor anchor) {
        engine.getTaskService().complete(anchor.taskId);
    }

    private static String singleActiveTaskId(ProcessEngine engine, String processInstanceId) {
        return engine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult()
                .getId();
    }

    /** 经引擎公开位点写入一行决策证据（与框架写入器同一调用形态），返回该行 ID_。 */
    private static String writeEvidenceRow(ProcessEngine engine, Anchor anchor, String idempotencyKey,
                                           String rowMark) {
        DecisionEvidenceVO evidence = DecisionEvidenceVO.builder()
                .outcome(DecisionOutcome.SUGGESTION_PRODUCED)
                .schemaVersion(1)
                .idempotencyKey(idempotencyKey)
                .actionSummary("E12 读序 fixture：" + rowMark)
                .rationaleNarrative("读侧顺序 fixture 依据")
                .build();
        String rowText = DecisionEvidenceComment.marker() + toJson(evidence);
        Comment created = engine.getTaskService().addComment(anchor.taskId, anchor.processInstanceId,
                DecisionEvidenceComment.COMMENT_TYPE.name(), rowText);
        return created.getId();
    }

    private static String toJson(DecisionEvidenceVO evidence) {
        try {
            return MAPPER.writeValueAsString(evidence);
        } catch (Exception broken) {
            throw new IllegalStateException("E12 fixture 的证据行必须可序列化", broken);
        }
    }

    /** 直连内存库改写评论行时间（fixture 构造；引擎无「改写历史行时间」的公开位点）。 */
    private static void rewriteTimes(ProcessEngine engine, String rowIdA, long timeA,
                                     String rowIdB, long timeB) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                ExtensionTestEngine.jdbcUrl(), "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE ACT_HI_COMMENT SET TIME_ = ? WHERE ID_ = ?")) {
            setRowTime(statement, rowIdA, timeA);
            setRowTime(statement, rowIdB, timeB);
        }
    }

    private static void setRowTime(PreparedStatement statement, String rowId, long time) throws Exception {
        statement.setTimestamp(1, new java.sql.Timestamp(time));
        statement.setString(2, rowId);
        statement.executeUpdate();
    }

    /** 引擎原样返回的评论序（不重排 —— 正是被测事实的输入）。 */
    private static List<Comment> rawEngineComments(ProcessEngine engine, String processInstanceId) {
        return engine.getTaskService().getProcessInstanceComments(processInstanceId);
    }

    private static List<Date> times(List<Comment> comments) {
        return comments.stream().map(Comment::getTime).collect(java.util.stream.Collectors.toList());
    }

    private static boolean idsAscending(List<Comment> comments) {
        Long previous = null;
        for (final Comment comment : comments) {
            Long current = numericId(comment.getId());
            if (current == null || (previous != null && current <= previous)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static Long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException nonNumeric) {
            return null;
        }
    }

    /** 读侧入口（框架受控读面）：取锚点任务的审批记录。 */
    private static ApprovalRecordVO anchorRecord(ProcessEngine engine, Anchor anchor) {
        HistoryWorkflow historyWorkflow = new HistoryWorkflow(
                engine.getHistoryService(),
                engine.getTaskService(),
                new DefaultBpmnModelCache(engine.getRepositoryService()),
                new MultiInstanceDetector(new DefaultBpmnModelCache(engine.getRepositoryService()),
                        engine.getTaskService(), engine.getHistoryService()),
                (IdentityResolver) userId -> userId,
                new DefaultActionInferenceStrategy(),
                new CountersignRoundResolver(engine.getHistoryService(), engine.getTaskService()));
        List<ApprovalRecordVO> records = historyWorkflow.getApprovalHistory(anchor.processInstanceId);
        return records.stream()
                .filter(record -> anchor.taskId.equals(record.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("锚点任务的审批记录必须存在"));
    }
}
