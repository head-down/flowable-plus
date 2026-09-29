package io.github.flowable.plus.core;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionEvidenceReadGuard;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.SourceScanSupport;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.CountersignRoundResolver;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.support.DefaultActionInferenceStrategy;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionEvidenceTestFixtures;
import io.github.flowable.plus.core.vo.UnorderedDecisionEvidences;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 决策证据的<b>读侧投影</b>守卫（ADR-0042 第 5 节读取面：容错条款 / 挂载层级 / 重放判定 /
 * 读写两侧护栏）。纯值 + mock 读侧输入，不引 DB、不引 Spring（core 保持纯单测分层）。
 *
 * <p>本类的多重身份：{@code #resolveReplayOfReturnsOriginalOrNull()} 与
 * {@code #distinctKeysAreNeverJudgedAsReplay()} 钉住读侧派生判定；{@code #corruptedRowSkipsProjectionOnly()}
 * 与 {@code #unknownSecondaryEnumDegradesToNullOnly()} 钉住字段级容错；{@code #countersignRowsAttachToSubRecordOnly()}
 * 钉住挂载层级；{@code #decisionEvidencesDefaultsToEmptyCollection()} 钉住软回退；
 * {@code #eachEvidenceCarriesItsOwnCommentRowTime()} 与 {@code #unorderedAnchorStillCarriesRowTime()}
 * 钉住读侧专属时间字段（逐行带出，且「序不可判」≠「时间缺失」）；
 * {@code #overLimitRowSkipsProjectionButKeepsHistoryRow()} 钉住 G6；{@code #guardAppliedBeforeParsing()}
 * 以受限源码扫描钉住 G7（护栏先于任何 JSON 解析）。</p>
 */
public class HistoryWorkflowEvidenceReadTest {

    private static final String INSTANCE_ID = "pi-evidence-001";
    private static final String PROCESS_DEF_ID = "leave:1:abc123";
    private static final String START_USER_ID = "initiator";

    /** 最小可投影的产出态证据载荷（判别式合法即投影，其余字段留空） */
    private static final String MINIMAL_PRODUCED_EVIDENCE_JSON =
            "{\"outcome\":\"SUGGESTION_PRODUCED\",\"schemaVersion\":1,\"idempotencyKey\":\"idem-1\"}";

    private HistoryService mockHistoryService;
    private TaskService mockTaskService;
    private BpmnModelCache mockBpmnModelCache;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private HistoryWorkflow historyWorkflow;

    @BeforeEach
    public void setUp() {
        mockHistoryService = mock(HistoryService.class);
        mockTaskService = mock(TaskService.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        IdentityResolver mockIdentityResolver = mock(IdentityResolver.class);
        when(mockIdentityResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));

        historyWorkflow = new HistoryWorkflow(mockHistoryService, mockTaskService,
                mockBpmnModelCache, mockMultiInstanceDetector, mockIdentityResolver,
                new DefaultActionInferenceStrategy(),
                new CountersignRoundResolver(mockHistoryService, mockTaskService));

        HistoricVariableInstanceQuery varQuery = mock(HistoricVariableInstanceQuery.class);
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQuery);
        when(varQuery.processInstanceId(INSTANCE_ID)).thenReturn(varQuery);
        when(varQuery.variableName(any())).thenReturn(varQuery);
        when(varQuery.list()).thenReturn(Collections.emptyList());
    }

    // ======================== 读侧派生判定（重放） ========================

    @Test
    void resolveReplayOfReturnsOriginalOrNull() {
        DecisionEvidenceVO original = evidence("idem-1");
        DecisionEvidenceVO replay = evidence("idem-1");
        ApprovalRecordVO record = ApprovalRecordVO.builder()
                .taskId("t-1")
                .decisionEvidences(Arrays.asList(original, replay))
                .build();

        // 原行 ⇒ null
        assertThat(record.resolveReplayOf(original)).isNull();
        // 重放 ⇒ 返回原行
        assertThat(record.resolveReplayOf(replay)).isSameAs(original);
        // 身份为空 ⇒ 不判
        assertThat(record.resolveReplayOf(evidence(null))).isNull();
        assertThat(record.resolveReplayOf(null)).isNull();

        // 会签子记录上同形（判定挂在证据实际所在的层级）
        CountersignSubRecord subRecord = CountersignSubRecord.builder()
                .taskId("t-1")
                .decisionEvidences(Arrays.asList(original, replay))
                .build();
        assertThat(subRecord.resolveReplayOf(original)).isNull();
        assertThat(subRecord.resolveReplayOf(replay)).isSameAs(original);
    }

    @Test
    void distinctKeysAreNeverJudgedAsReplay() {
        DecisionEvidenceVO first = evidence("idem-1");
        DecisionEvidenceVO second = evidence("idem-2");
        ApprovalRecordVO record = ApprovalRecordVO.builder()
                .taskId("t-1")
                .decisionEvidences(Arrays.asList(first, second))
                .build();

        // 键不同 ⇒ 两次独立决策，互不判为同一次
        assertThat(record.resolveReplayOf(first)).isNull();
        assertThat(record.resolveReplayOf(second)).isNull();
    }

    // ======================== 字段级容错 ========================

    @Test
    void corruptedRowSkipsProjectionOnly() throws Exception {
        Comment evidenceRow = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + "{not-json", 3000, "3");
        Comment agree = comment("ht-1", CommentType.AGREE.name(), "同意通过", 2000, "2");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(),
                Arrays.asList(agree, evidenceRow));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        ApprovalRecordVO record = result.get(1);
        // 载荷损坏 ⇒ 只跳过该条证据投影
        assertThat(record.getDecisionEvidences()).isEmpty();
        // 审批轨迹行本身不消失：action / comment 照常产出
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record.getComment()).isEqualTo("同意通过");
        assertThat(record.getTaskId()).isEqualTo("ht-1");
    }

    @Test
    void unknownSecondaryEnumDegradesToNullOnly() {
        String json = "{\"outcome\":\"SUGGESTION_PRODUCED\",\"schemaVersion\":1,\"idempotencyKey\":\"idem-1\","
                + "\"policyReason\":\"NOT_A_VALUE\",\"failureKind\":\"NOT_A_VALUE\",\"chainStage\":\"NOT_A_VALUE\","
                + "\"subjectType\":\"NOT_A_VALUE\",\"outboundCompleteness\":\"NOT_A_VALUE\","
                + "\"inboundCompleteness\":\"NOT_A_VALUE\"}";
        Comment evidenceRow = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + json, 3000, "3");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(), Collections.singletonList(evidenceRow));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        ApprovalRecordVO record = result.get(1);
        // 次要枚举未知 ⇒ 字段级降级 null，该条证据仍投影
        assertThat(record.getDecisionEvidences()).hasSize(1);
        DecisionEvidenceVO evidence = record.getDecisionEvidences().get(0);
        assertThat(evidence.getOutcome()).isNotNull();
        assertThat(evidence.getPolicyReason()).isNull();
        assertThat(evidence.getFailureKind()).isNull();
        assertThat(evidence.getChainStage()).isNull();
        assertThat(evidence.getSubjectType()).isNull();
        assertThat(evidence.getOutboundCompleteness()).isNull();
        assertThat(evidence.getInboundCompleteness()).isNull();
    }

    @Test
    void unknownOutcomeSkipsProjectionOnly() {
        String json = "{\"outcome\":\"NOT_A_VALUE\",\"schemaVersion\":1,\"idempotencyKey\":\"idem-1\"}";
        Comment evidenceRow = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + json, 3000, "3");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(), Collections.singletonList(evidenceRow));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        ApprovalRecordVO record = result.get(1);
        // 判别式不可解析 ⇒ 整条跳过（判别式不做字段级降级，否则读者要用未知判别式去读三种结局）
        assertThat(record.getDecisionEvidences()).isEmpty();
        // 审批轨迹行本身不消失（此处无业务意见，故 action 走 deleteReason 兜底）
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record.getTaskId()).isEqualTo("ht-1");
    }

    @Test
    void higherSchemaVersionIsStillProjected() {
        String json = "{\"outcome\":\"SUGGESTION_PRODUCED\",\"schemaVersion\":999,\"idempotencyKey\":\"idem-1\","
                + "\"actionSummary\":\"同意\",\"futureField\":\"未知字段\"}";
        Comment evidenceRow = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + json, 3000, "3");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(), Collections.singletonList(evidenceRow));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        // schemaVersion 高于本读侧已知最大值 + 未知字段 ⇒ 尽力读、不做版本门禁（审计面最怕读不出来）
        assertThat(result.get(1).getDecisionEvidences()).hasSize(1);
        DecisionEvidenceVO evidence = result.get(1).getDecisionEvidences().get(0);
        assertThat(evidence.getSchemaVersion()).isEqualTo(999);
        assertThat(evidence.getActionSummary()).isEqualTo("同意");
    }

    // ======================== 读侧专属字段：由评论行 TIME_ 逐行填充 ========================

    @Test
    void eachEvidenceCarriesItsOwnCommentRowTime() {
        Comment earlier = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + MINIMAL_PRODUCED_EVIDENCE_JSON, 2000, "1");
        Comment later = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + MINIMAL_PRODUCED_EVIDENCE_JSON, 4000, "2");

        // 引擎原样序 = TIME_ 降序（D4）：读侧须自行重排，且每行带出自己的那一份时间
        stubNormalFlow(singleTaskActivities(), singleHistoricTask(), Arrays.asList(later, earlier));
        stubSingleNodeModel();

        List<DecisionEvidenceVO> evidences = historyWorkflow.getApprovalHistory(INSTANCE_ID)
                .get(1).getDecisionEvidences();

        // 两行载荷逐字相同（同一幂等键、同一 JSON）⇒ 时间只可能来自各自那一条评论行
        assertThat(evidences).hasSize(2);
        assertThat(evidences.get(0).getRecordedTime()).isEqualTo(new Date(2000));
        assertThat(evidences.get(1).getRecordedTime()).isEqualTo(new Date(4000));
    }

    @Test
    void unorderedAnchorStillCarriesRowTime() {
        Comment first = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + MINIMAL_PRODUCED_EVIDENCE_JSON, 2000, "gen-1");
        Comment second = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + MINIMAL_PRODUCED_EVIDENCE_JSON, 4000, "gen-2");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(), Arrays.asList(first, second));
        stubSingleNodeModel();

        List<DecisionEvidenceVO> evidences = historyWorkflow.getApprovalHistory(INSTANCE_ID)
                .get(1).getDecisionEvidences();

        // ID_ 非数值 ⇒ 不建序（拿到什么序就是什么序）；但「序不可判」≠「时间缺失」：时间照常逐行带出
        assertThat(evidences).isInstanceOf(UnorderedDecisionEvidences.class);
        assertThat(evidences).extracting(DecisionEvidenceVO::getRecordedTime)
                .containsExactlyInAnyOrder(new Date(2000), new Date(4000));
    }

    // ======================== 挂载层级 ========================

    @Test
    void countersignRowsAttachToSubRecordOnly() throws Exception {
        Date startEventTime = new Date(1000);
        Date csStart1 = new Date(2000);
        Date csEnd1 = new Date(3000);
        Date csStart2 = new Date(2100);
        Date csEnd2 = new Date(3100);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "userTask", "会签审批", csStart1, csStart1, "ht-cs-1"),
                createActivity("csTask", "userTask", "会签审批", csStart2, csStart2, "ht-cs-2"));
        List<HistoricTaskInstance> tasks = Arrays.asList(
                createHistoricTask("ht-cs-1", "csTask", "会签审批", "userA", csStart1, csEnd1, "completed"),
                createHistoricTask("ht-cs-2", "csTask", "会签审批", "userB", csStart2, csEnd2, "completed"));

        Comment vote1 = comment("ht-cs-1", CommentType.COUNTER_SIGN_AGREE.name(), "同意", csEnd1, "1");
        Comment evidenceRow = comment("ht-cs-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + DecisionEvidenceTestFixtures.toJson(
                        DecisionEvidenceTestFixtures.maximalDirectSubmission()), csEnd1.getTime() + 100, "2");
        Comment vote2 = comment("ht-cs-2", CommentType.COUNTER_SIGN_AGREE.name(), "同意", csEnd2, "3");

        stubNormalFlow(activities, tasks, Arrays.asList(vote1, evidenceRow, vote2));
        stubMultiInstanceModel();
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        ApprovalRecordVO parent = result.get(1);
        // 会签节点：证据挂子记录，父 VO 恒空
        assertThat(parent.getDecisionEvidences()).isEmpty();
        CountersignSubRecord sub1 = parent.getCountersignRecords().get(0);
        assertThat(sub1.getDecisionEvidences()).hasSize(1);
        // 同一条证据不重复挂两层
        assertThat(parent.getCountersignRecords().get(1).getDecisionEvidences()).isEmpty();
    }

    // ======================== 软回退：恒返回空集合 ========================

    @Test
    void decisionEvidencesDefaultsToEmptyCollection() {
        // 无参构造（Jackson 一类反序列化路径）/ builder 路径都不得返回 null
        assertThat(new ApprovalRecordVO().getDecisionEvidences()).isEmpty();
        assertThat(ApprovalRecordVO.builder().build().getDecisionEvidences()).isEmpty();
        assertThat(new CountersignSubRecord().getDecisionEvidences()).isEmpty();
        assertThat(CountersignSubRecord.builder().build().getDecisionEvidences()).isEmpty();

        // 证据面恒无输入（无证据行）时，读侧产出的记录同样返回空集合
        stubNormalFlow(singleTaskActivities(), singleHistoricTask(),
                Collections.singletonList(comment("ht-1", CommentType.AGREE.name(), "同意", 3000, "1")));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(record -> assertThat(record.getDecisionEvidences()).isEmpty());
    }

    // ======================== G6：超限只跳过该条投影 ========================

    @Test
    void overLimitRowSkipsProjectionButKeepsHistoryRow() {
        Comment oversized = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + oversizedJson(), 3000, "3");
        Comment tooDeep = comment("ht-1", CommentType.DECISION_EVIDENCE.name(),
                DecisionEvidenceComment.marker() + tooDeepJson(), 4000, "4");
        Comment agree = comment("ht-1", CommentType.AGREE.name(), "同意通过", 2000, "2");

        stubNormalFlow(singleTaskActivities(), singleHistoricTask(),
                Arrays.asList(agree, oversized, tooDeep));
        stubSingleNodeModel();

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        ApprovalRecordVO record = result.get(1);
        // 超大小上限 / 超嵌套深度上限两条都只跳过自身
        assertThat(record.getDecisionEvidences()).isEmpty();
        // 审批轨迹行不消失
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record.getComment()).isEqualTo("同意通过");
    }

    // ======================== G7：护栏先于解析（受限源码扫描） ========================

    @Test
    void guardAppliedBeforeParsing() {
        List<SourceScanSupport.Hit> sizeGuards =
                SourceScanSupport.scanMainSources("DecisionEvidenceReadGuard.MAX_PARSE_BYTES");
        List<SourceScanSupport.Hit> depthGuards =
                SourceScanSupport.scanMainSources("DecisionEvidenceReadGuard.MAX_NESTING_DEPTH");
        List<SourceScanSupport.Hit> parses = SourceScanSupport.scanMainSources("readTree");

        assertThat(distinctPaths(sizeGuards))
                .as("大小护栏必须恰好落在一处读侧投影实现里")
                .hasSize(1);
        assertThat(distinctPaths(depthGuards))
                .as("深度护栏必须恰好落在一处读侧投影实现里")
                .hasSize(1);
        assertThat(distinctPaths(parses))
                .as("整树解析（readTree）必须恰好落在一处")
                .hasSize(1);
        assertThat(distinctPaths(depthGuards)).containsExactlyElementsOf(distinctPaths(sizeGuards));
        assertThat(distinctPaths(parses)).containsExactlyElementsOf(distinctPaths(sizeGuards));

        int lastGuardLine = sizeGuards.stream()
                .mapToInt(SourceScanSupport.Hit::getLineNumber)
                .max()
                .orElseThrow(AssertionError::new);
        int firstParseLine = parses.stream()
                .mapToInt(SourceScanSupport.Hit::getLineNumber)
                .min()
                .orElseThrow(AssertionError::new);
        assertThat(firstParseLine)
                .as("两道护栏的检查必须写在任何 JSON 解析之前（不得先整体解析再判大小）")
                .isGreaterThan(lastGuardLine);
        assertThat(depthGuards.stream().mapToInt(SourceScanSupport.Hit::getLineNumber).max().orElseThrow(AssertionError::new))
                .isLessThan(firstParseLine);
    }

    private static List<String> distinctPaths(List<SourceScanSupport.Hit> hits) {
        return hits.stream()
                .map(SourceScanSupport.Hit::getPath)
                .distinct()
                .collect(Collectors.toList());
    }

    // ======================== Fixtures ========================

    private static DecisionEvidenceVO evidence(String idempotencyKey) {
        return DecisionEvidenceVO.builder()
                .idempotencyKey(idempotencyKey)
                .policyReason(null)
                .build();
    }

    /** 超出读侧大小上限的证据载荷（固定字符串常量，不落资源文件）。 */
    private static String oversizedJson() {
        String pad = StringUtils.repeat('x', DecisionEvidenceReadGuard.MAX_PARSE_BYTES + 1);
        return "{\"outcome\":\"SUGGESTION_PRODUCED\",\"idempotencyKey\":\"idem-big\","
                + "\"actionSummary\":\"" + pad + "\"}";
    }

    /** 超出读侧嵌套深度上限的证据载荷。 */
    private static String tooDeepJson() {
        int depth = DecisionEvidenceReadGuard.MAX_NESTING_DEPTH + 2;
        String nested = StringUtils.repeat('[', depth) + StringUtils.repeat(']', depth);
        return "{\"outcome\":\"SUGGESTION_PRODUCED\",\"idempotencyKey\":\"idem-deep\","
                + "\"extra\":" + nested + "}";
    }

    // ======================== Test Helpers ========================

    private List<HistoricActivityInstance> singleTaskActivities() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        return Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-1"));
    }

    private List<HistoricTaskInstance> singleHistoricTask() {
        return Collections.singletonList(createHistoricTask("ht-1", "task1", "部门审批", "user1",
                new Date(2000), new Date(3000), "completed"));
    }

    private Comment comment(String taskId, String type, String fullMessage, long time, String id) {
        return comment(taskId, type, fullMessage, new Date(time), id);
    }

    private Comment comment(String taskId, String type, String fullMessage, Date time, String id) {
        Comment comment = mock(Comment.class);
        when(comment.getTaskId()).thenReturn(taskId);
        when(comment.getType()).thenReturn(type);
        when(comment.getFullMessage()).thenReturn(fullMessage);
        when(comment.getTime()).thenReturn(time);
        when(comment.getId()).thenReturn(id);
        return comment;
    }

    private HistoricActivityInstance createActivity(String activityId, String activityType,
                                                     String activityName, Date startTime,
                                                     Date endTime, String taskId) {
        HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
        when(activity.getActivityId()).thenReturn(activityId);
        when(activity.getActivityType()).thenReturn(activityType);
        when(activity.getActivityName()).thenReturn(activityName);
        when(activity.getStartTime()).thenReturn(startTime);
        when(activity.getEndTime()).thenReturn(endTime);
        when(activity.getTaskId()).thenReturn(taskId);
        when(activity.getProcessDefinitionId()).thenReturn(PROCESS_DEF_ID);
        when(activity.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        return activity;
    }

    private HistoricTaskInstance createHistoricTask(String taskId, String taskDefKey, String taskName,
                                                     String assignee, Date createTime, Date endTime,
                                                     String deleteReason) {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(task.getName()).thenReturn(taskName);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getCreateTime()).thenReturn(createTime);
        when(task.getEndTime()).thenReturn(endTime);
        when(task.getDeleteReason()).thenReturn(deleteReason);
        return task;
    }

    private void stubNormalFlow(List<HistoricActivityInstance> activities,
                                List<HistoricTaskInstance> historicTasks,
                                List<Comment> comments) {
        stubProcessInstanceExists();
        HistoricActivityInstanceQuery activityQuery = mock(HistoricActivityInstanceQuery.class);
        when(mockHistoryService.createHistoricActivityInstanceQuery()).thenReturn(activityQuery);
        when(activityQuery.processInstanceId(INSTANCE_ID)).thenReturn(activityQuery);
        when(activityQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(activityQuery);
        when(activityQuery.asc()).thenReturn(activityQuery);
        when(activityQuery.list()).thenReturn(new ArrayList<>(activities));

        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(INSTANCE_ID)).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceStartTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.asc()).thenReturn(histTaskQuery);
        when(histTaskQuery.list()).thenReturn(new ArrayList<>(historicTasks));

        when(mockTaskService.getProcessInstanceComments(INSTANCE_ID)).thenReturn(new ArrayList<>(comments));
    }

    private void stubProcessInstanceExists() {
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getStartUserId()).thenReturn(START_USER_ID);
        when(hpi.getProcessDefinitionId()).thenReturn(PROCESS_DEF_ID);

        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId(INSTANCE_ID)).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);
    }

    private void stubSingleNodeModel() {
        when(mockBpmnModelCache.getBpmnModel(PROCESS_DEF_ID)).thenReturn(buildSimpleModel());
    }

    private void stubMultiInstanceModel() {
        when(mockBpmnModelCache.getBpmnModel(PROCESS_DEF_ID)).thenReturn(buildMultiInstanceModel());
    }

    private static BpmnModel buildSimpleModel() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask task1 = new UserTask();
        task1.setId("task1");
        process.addFlowElement(task1);
        addFlow(process, "f1", "start", "task1");

        return model;
    }

    private static BpmnModel buildMultiInstanceModel() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask task1 = new UserTask();
        task1.setId("task1");
        process.addFlowElement(task1);
        addFlow(process, "f1", "start", "task1");

        UserTask csTask = new UserTask();
        csTask.setId("csTask");
        MultiInstanceLoopCharacteristics mic = new MultiInstanceLoopCharacteristics();
        mic.setSequential(false);
        csTask.setLoopCharacteristics(mic);
        process.addFlowElement(csTask);
        addFlow(process, "f2", "task1", "csTask");

        return model;
    }

    private static void addFlow(Process process, String id, String source, String target) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(source);
        flow.setTargetRef(target);
        process.addFlowElement(flow);
    }
}
