package io.github.flowable.plus.core;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.CountersignRoundResolver;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.support.ActionInferenceStrategy;
import io.github.flowable.plus.core.support.DefaultActionInferenceStrategy;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HistoryWorkflow 单元测试：基于 Mock 验证审批历史查询全链路。
 *
 * <p>覆盖三级 Comment→Action 推断（ADR-0009）、会签贪心归组、
 * START 特殊处理、活跃节点检测、异常路径等全场景。</p>
 */
public class HistoryWorkflowTest {

    private static final String INSTANCE_ID = "pi-test-001";
    private static final String PROCESS_DEF_ID = "leave:1:abc123";
    private static final String START_USER_ID = "initiator";

    private HistoryService mockHistoryService;
    private TaskService mockTaskService;
    private BpmnModelCache mockBpmnModelCache;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private IdentityResolver mockIdentityResolver;
    private HistoryWorkflow historyWorkflow;

    /** 用于区分不同 variableName 的 mock 数据 */
    private List<HistoricVariableInstance> csRoundIndexVarData = Collections.emptyList();

    @BeforeEach
    public void setUp() {
        mockHistoryService = mock(HistoryService.class);
        mockTaskService = mock(TaskService.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        mockIdentityResolver = mock(IdentityResolver.class);

        // 默认身份解析：userId → userId + "Name"
        when(mockIdentityResolver.resolve("initiator")).thenReturn("发起人");
        when(mockIdentityResolver.resolve("user1")).thenReturn("用户一");
        when(mockIdentityResolver.resolve("user2")).thenReturn("用户二");
        when(mockIdentityResolver.resolve("userA")).thenReturn("用户A");
        when(mockIdentityResolver.resolve("userB")).thenReturn("用户B");
        when(mockIdentityResolver.resolve("userC")).thenReturn("用户C");

        ActionInferenceStrategy actionInferenceStrategy = new DefaultActionInferenceStrategy();

        historyWorkflow = new HistoryWorkflow(mockHistoryService, mockTaskService,
                mockBpmnModelCache, mockMultiInstanceDetector, mockIdentityResolver,
                actionInferenceStrategy, new CountersignRoundResolver(mockHistoryService, mockTaskService));

        // 每次 createHistoricVariableInstanceQuery() 创建新 mock，通过追踪 variableName 区分返回值
        resetVarQueryStubs();
    }

    /**
     * 将 createHistoricVariableInstanceQuery() 设置为按 variableName 返回不同数据的机制。
     * 在需要自定义变量数据的测试中调用对应 stub 后，会自动生效。
     */
    private void resetVarQueryStubs() {
        // 重置数据
        csRoundIndexVarData = Collections.emptyList();

        // 使用可变引用追踪最后调用的 variableName
        final String[] lastVarName = {null};
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenAnswer(inv -> {
            HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
            when(q.processInstanceId(INSTANCE_ID)).thenReturn(q);
            when(q.variableName(any())).thenAnswer(varNameInv -> {
                lastVarName[0] = (String) varNameInv.getArgument(0);
                return q;
            });
            when(q.list()).thenAnswer(listInv -> {
                if ("csRoundIndex".equals(lastVarName[0])) {
                    return csRoundIndexVarData;
                }
                return Collections.emptyList();
            });
            return q;
        });
    }

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullProcessInstanceId() {
        assertThatThrownBy(() -> historyWorkflow.getApprovalHistory(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId 不可为 null 或空");
    }

    @Test
    public void testRejectEmptyProcessInstanceId() {
        assertThatThrownBy(() -> historyWorkflow.getApprovalHistory(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId 不可为 null 或空");
    }

    @Test
    public void testNotFoundProcessInstance() {
        stubProcessInstanceNotFound();

        assertThatThrownBy(() -> historyWorkflow.getApprovalHistory(INSTANCE_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(INSTANCE_ID);
    }

    // ======================== 正常流程：多节点 + START 记录 ========================

    @Test
    public void testNormalMultiNodeFlow() {
        Date startEventTime = new Date(1000);
        Date task1Start = new Date(2000);
        Date task1End = new Date(3000);
        Date task2Start = new Date(4000);
        Date task2End = new Date(5000);

        // 活动实例：startEvent → task1 → task2
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", task1Start, task1Start, "ht-task1"),
                createActivity("task2", "userTask", "经理审批", task2Start, task2Start, "ht-task2")
        );

        // 历史任务
        HistoricTaskInstance ht1 = createHistoricTask("ht-task1", "task1", "部门审批", "user1",
                task1Start, task1End, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-task2", "task2", "经理审批", "user2",
                task2Start, task2End, "completed");

        // Comment 含业务类型
        Comment comment1 = createComment("ht-task1", "AGREE", "同意通过", task1End);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2), Collections.singletonList(comment1));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(3);

        // START 记录
        ApprovalRecordVO startRecord = result.get(0);
        assertThat(startRecord.getAction()).isEqualTo(ApprovalAction.START);
        assertThat(startRecord.getNodeId()).isEqualTo("start");
        assertThat(startRecord.getActorId()).isEqualTo(START_USER_ID);
        assertThat(startRecord.getActorName()).isEqualTo("发起人");
        assertThat(startRecord.getTaskId()).isNull();

        // 任务1（Comment 推断 AGREE）
        ApprovalRecordVO record1 = result.get(1);
        assertThat(record1.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record1.getNodeId()).isEqualTo("task1");
        assertThat(record1.getActorId()).isEqualTo("user1");
        assertThat(record1.getActorName()).isEqualTo("用户一");
        assertThat(record1.getComment()).isEqualTo("同意通过");
        assertThat(record1.getStartTime()).isEqualTo(task1Start);
        assertThat(record1.getEndTime()).isEqualTo(task1End);
        assertThat(record1.getDuration()).isEqualTo(1000L);

        // 任务2（DeleteReason 兜底 AGREE）
        ApprovalRecordVO record2 = result.get(2);
        assertThat(record2.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record2.getNodeId()).isEqualTo("task2");
        assertThat(record2.getActorId()).isEqualTo("user2");
        assertThat(record2.getActorName()).isEqualTo("用户二");
        assertThat(record2.getComment()).isNull();
    }

    // ======================== 会签流程 ========================

    @Test
    public void testCounterSignFlow() {
        Date startEventTime = new Date(1000);
        Date csStart1 = new Date(2000);
        Date csEnd1 = new Date(3000);
        Date csStart2 = new Date(2100);
        Date csEnd2 = new Date(3100);
        Date csStart3 = new Date(2200);
        Date csEnd3 = new Date(3200);

        // 活动实例：startEvent → csTask(多实例，3个实例)
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "userTask", "会签审批", csStart1, csStart1, "ht-cs-1"),
                createActivity("csTask", "userTask", "会签审批", csStart2, csStart2, "ht-cs-2"),
                createActivity("csTask", "userTask", "会签审批", csStart3, csStart3, "ht-cs-3")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-cs-1", "csTask", "会签审批", "userA",
                csStart1, csEnd1, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-cs-2", "csTask", "会签审批", "userB",
                csStart2, csEnd2, "completed");
        HistoricTaskInstance ht3 = createHistoricTask("ht-cs-3", "csTask", "会签审批", "userC",
                csStart3, csEnd3, "completed");

        Comment comment1 = createComment("ht-cs-1", "COUNTER_SIGN_AGREE", "同意", csEnd1);
        Comment comment2 = createComment("ht-cs-2", "COUNTER_SIGN_AGREE", "同意", csEnd2);
        Comment comment3 = createComment("ht-cs-3", "COUNTER_SIGN_REJECT", "不同意", csEnd3);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2, ht3),
                Arrays.asList(comment1, comment2, comment3));
        stubBpmnModel(buildMultiInstanceModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2); // START + 会签父记录

        // START 记录
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        // 会签父记录
        ApprovalRecordVO csParent = result.get(1);
        assertThat(csParent.getNodeId()).isEqualTo("csTask");
        assertThat(csParent.getNodeName()).isEqualTo("会签审批");
        assertThat(csParent.getAction()).isNull();
        assertThat(csParent.getActorId()).isNull();
        assertThat(csParent.getCountersignRecords()).hasSize(3);

        // 子记录按顺序
        CountersignSubRecord sub1 = csParent.getCountersignRecords().get(0);
        assertThat(sub1.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub1.getActorId()).isEqualTo("userA");
        assertThat(sub1.getActorName()).isEqualTo("用户A");
        assertThat(sub1.getComment()).isEqualTo("同意");

        CountersignSubRecord sub2 = csParent.getCountersignRecords().get(1);
        assertThat(sub2.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub2.getActorId()).isEqualTo("userB");

        CountersignSubRecord sub3 = csParent.getCountersignRecords().get(2);
        assertThat(sub3.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
        assertThat(sub3.getActorId()).isEqualTo("userC");
    }

    // ======================== ADR-0025：操作注释不抢占业务意见槽位 ========================

    @Test
    public void testCounterSignSubRecordWithOperationCommentNotPollutingComment() {
        Date startEventTime = new Date(1000);
        Date csStart1 = new Date(2000);
        Date csEnd1 = new Date(3000);
        Date csStart2 = new Date(2100);
        Date csEnd2 = new Date(3100);

        // 活动实例：startEvent → csTask(多实例，2个实例)
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "userTask", "会签审批", csStart1, csStart1, "ht-cs-1"),
                createActivity("csTask", "userTask", "会签审批", csStart2, csStart2, "ht-cs-2")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-cs-1", "csTask", "会签审批", "userA",
                csStart1, csEnd1, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-cs-2", "csTask", "会签审批", "userB",
                csStart2, csEnd2, "completed");

        // ht-cs-1 同时存在业务意见（投票时写入）与更晚的 ADD_SIGN 操作注释
        Comment comment1 = createComment("ht-cs-1", "COUNTER_SIGN_AGREE", "同意", csEnd1);
        Comment addSign = createComment("ht-cs-1", "ADD_SIGN", "加签审批人: userC", new Date(csEnd1.getTime() + 100));
        Comment comment2 = createComment("ht-cs-2", "COUNTER_SIGN_AGREE", "同意", csEnd2);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2),
                Arrays.asList(comment1, addSign, comment2));
        stubBpmnModel(buildMultiInstanceModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2); // START + 会签父记录
        ApprovalRecordVO csParent = result.get(1);
        assertThat(csParent.getCountersignRecords()).hasSize(2);

        // 子记录1：comment 返回真实业务意见，operationComment 独立承载加签信息，action 为业务投票动作
        CountersignSubRecord sub1 = csParent.getCountersignRecords().get(0);
        assertThat(sub1.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub1.getComment()).isEqualTo("同意");
        assertThat(sub1.getOperationComment()).isEqualTo("加签审批人: userC");
        assertThat(sub1.getOperationComments()).containsExactly("加签审批人: userC");

        // 子记录2：无操作注释，operationComment 与 operationComments 均为 null
        CountersignSubRecord sub2 = csParent.getCountersignRecords().get(1);
        assertThat(sub2.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub2.getComment()).isEqualTo("同意");
        assertThat(sub2.getOperationComment()).isNull();
        assertThat(sub2.getOperationComments()).isNull();
    }

    @Test
    public void testActiveTaskWithOnlyOperationComment() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);

        // 活跃任务（未投票，仅加签注释）：startEvent → task1
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-task1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-task1", "task1", "部门审批", "user1",
                taskStart, null, null);

        Comment addSign = createComment("ht-task1", "ADD_SIGN", "加签审批人: user2", taskStart);

        stubNormalFlow(activities, Collections.singletonList(ht1), Collections.singletonList(addSign));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        ApprovalRecordVO record = result.get(1);
        // 活跃节点仅加签：action 仍识别为 ADD_SIGN，comment 为空，operationComment 独立承载
        assertThat(record.getAction()).isEqualTo(ApprovalAction.ADD_SIGN);
        assertThat(record.getComment()).isNull();
        assertThat(record.getOperationComment()).isEqualTo("加签审批人: user2");
        assertThat(record.getOperationComments()).containsExactly("加签审批人: user2");
    }

    // ======================== ADR-0027：同一任务多次操作注释（连续加签/委派循环） ========================

    @Test
    public void testCounterSignSubRecordWithMultipleOperationComments() {
        Date startEventTime = new Date(1000);
        Date csStart1 = new Date(2000);
        Date csEnd1 = new Date(3000);
        Date csStart2 = new Date(2100);
        Date csEnd2 = new Date(3100);

        // 活动实例：startEvent → csTask(多实例，2个实例)
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "userTask", "会签审批", csStart1, csStart1, "ht-cs-1"),
                createActivity("csTask", "userTask", "会签审批", csStart2, csStart2, "ht-cs-2")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-cs-1", "csTask", "会签审批", "userA",
                csStart1, csEnd1, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-cs-2", "csTask", "会签审批", "userB",
                csStart2, csEnd2, "completed");

        // ht-cs-1 同一任务连续两次加签（时间正序），另含业务意见
        Comment comment1 = createComment("ht-cs-1", "COUNTER_SIGN_AGREE", "同意", csEnd1);
        Comment addSign1 = createComment("ht-cs-1", "ADD_SIGN", "加签审批人: userC", new Date(csEnd1.getTime() + 100));
        Comment addSign2 = createComment("ht-cs-1", "ADD_SIGN", "加签审批人: userD", new Date(csEnd1.getTime() + 200));
        Comment comment2 = createComment("ht-cs-2", "COUNTER_SIGN_AGREE", "同意", csEnd2);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2),
                Arrays.asList(comment1, addSign1, addSign2, comment2));
        stubBpmnModel(buildMultiInstanceModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2); // START + 会签父记录
        ApprovalRecordVO csParent = result.get(1);
        assertThat(csParent.getCountersignRecords()).hasSize(2);

        // 子记录1：连续两次加签全部保留（时间正序），单值 operationComment 为最新一条
        CountersignSubRecord sub1 = csParent.getCountersignRecords().get(0);
        assertThat(sub1.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub1.getComment()).isEqualTo("同意");
        assertThat(sub1.getOperationComment()).isEqualTo("加签审批人: userD");
        assertThat(sub1.getOperationComments())
                .containsExactly("加签审批人: userC", "加签审批人: userD");

        // 子记录2：无操作注释，operationComments 为 null
        CountersignSubRecord sub2 = csParent.getCountersignRecords().get(1);
        assertThat(sub2.getOperationComment()).isNull();
        assertThat(sub2.getOperationComments()).isNull();
    }

    @Test
    public void testNormalRecordWithDelegateAndResolveOperations() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        // 活动实例：startEvent → task1（普通节点）
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                taskStart, taskEnd, "completed");

        // 委派 → 收回委派（时间正序），另有最终业务意见
        Comment delegate = createComment("ht-1", "DELEGATE", "委派给: user2", new Date(2200));
        Comment resolve = createComment("ht-1", "RESOLVE_DELEGATE", "收回委派", new Date(2500));
        Comment agree = createComment("ht-1", "AGREE", "同意通过", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Arrays.asList(delegate, resolve, agree));
        stubBpmnModel(buildSimpleModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "task1")).thenReturn(false);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        ApprovalRecordVO record = result.get(1);
        // action/comment 仍取业务意见，操作注释由 operationComment / operationComments 独立承载
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record.getComment()).isEqualTo("同意通过");
        // 单值取最新一条（收回委派），多值按时间正序全量返回
        assertThat(record.getOperationComment()).isEqualTo("收回委派");
        assertThat(record.getOperationComments())
                .containsExactly("委派给: user2", "收回委派");
    }

    // ======================== 多轮会签：同一多实例节点被多次访问 ========================

    @Test
    public void testMultiRoundCounterSign() {
        Date startEventTime = new Date(1000);

        // 第1轮：miBody + 2个会签人
        Date miBodyR1Start = new Date(2000);
        Date r1Sub1Start = new Date(2100);
        Date r1Sub1End = new Date(2200);
        Date r1Sub2Start = new Date(2300);
        Date r1Sub2End = new Date(2400);

        // 第2轮：miBody + 2个会签人
        Date miBodyR2Start = new Date(5000);
        Date r2Sub1Start = new Date(5100);
        Date r2Sub1End = new Date(5200);
        Date r2Sub2Start = new Date(5300);
        Date r2Sub2End = new Date(5400);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                // 第1轮会签
                createActivity("csTask", "multiInstanceBody", "会签审批", miBodyR1Start, miBodyR1Start, null),
                createActivity("csTask", "userTask", "会签审批", r1Sub1Start, r1Sub1Start, "ht-r1-1"),
                createActivity("csTask", "userTask", "会签审批", r1Sub2Start, r1Sub2Start, "ht-r1-2"),
                // 第2轮会签
                createActivity("csTask", "multiInstanceBody", "会签审批", miBodyR2Start, miBodyR2Start, null),
                createActivity("csTask", "userTask", "会签审批", r2Sub1Start, r2Sub1Start, "ht-r2-1"),
                createActivity("csTask", "userTask", "会签审批", r2Sub2Start, r2Sub2Start, "ht-r2-2")
        );

        HistoricTaskInstance htR1_1 = createHistoricTask("ht-r1-1", "csTask", "会签审批", "userA",
                r1Sub1Start, r1Sub1End, "completed");
        HistoricTaskInstance htR1_2 = createHistoricTask("ht-r1-2", "csTask", "会签审批", "userB",
                r1Sub2Start, r1Sub2End, "completed");
        HistoricTaskInstance htR2_1 = createHistoricTask("ht-r2-1", "csTask", "会签审批", "userC",
                r2Sub1Start, r2Sub1End, "completed");
        HistoricTaskInstance htR2_2 = createHistoricTask("ht-r2-2", "csTask", "会签审批", "userD",
                r2Sub2Start, r2Sub2End, "completed");

        Comment cR1_1 = createComment("ht-r1-1", "COUNTER_SIGN_AGREE", "第1轮同意", r1Sub1End);
        Comment cR1_2 = createComment("ht-r1-2", "COUNTER_SIGN_AGREE", "第1轮同意", r1Sub2End);
        Comment cR2_1 = createComment("ht-r2-1", "COUNTER_SIGN_REJECT", "第2轮驳回", r2Sub1End);
        Comment cR2_2 = createComment("ht-r2-2", "COUNTER_SIGN_AGREE", "第2轮同意", r2Sub2End);

        when(mockIdentityResolver.resolve("userD")).thenReturn("用户D");

        // 第2轮会签任务 csRoundIndex=1（ADR-0020：轮次边界统一由 csRoundIndex 决定）
        List<HistoricVariableInstance> csRoundIndexVars = Arrays.asList(
                createCsRoundVariable("ht-r2-1", 1),
                createCsRoundVariable("ht-r2-2", 1));
        stubCsRoundIndexVars(csRoundIndexVars);

        stubNormalFlow(activities,
                Arrays.asList(htR1_1, htR1_2, htR2_1, htR2_2),
                Arrays.asList(cR1_1, cR1_2, cR2_1, cR2_2));
        stubBpmnModel(buildMultiInstanceModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        // 应产出 3 条父记录：START + 第1轮会签 + 第2轮会签
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        // 第1轮会签
        ApprovalRecordVO round1 = result.get(1);
        assertThat(round1.getNodeId()).isEqualTo("csTask");
        assertThat(round1.getCountersignRecords()).hasSize(2);
        assertThat(round1.getCountersignRecords().get(0).getActorId()).isEqualTo("userA");
        assertThat(round1.getCountersignRecords().get(0).getRoundIndex()).isEqualTo(0);
        assertThat(round1.getCountersignRecords().get(1).getActorId()).isEqualTo("userB");
        assertThat(round1.getCountersignRecords().get(1).getRoundIndex()).isEqualTo(0);

        // 第2轮会签
        ApprovalRecordVO round2 = result.get(2);
        assertThat(round2.getNodeId()).isEqualTo("csTask");
        assertThat(round2.getCountersignRecords()).hasSize(2);
        assertThat(round2.getCountersignRecords().get(0).getActorId()).isEqualTo("userC");
        assertThat(round2.getCountersignRecords().get(0).getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
        assertThat(round2.getCountersignRecords().get(0).getRoundIndex()).isEqualTo(1);
        assertThat(round2.getCountersignRecords().get(1).getActorId()).isEqualTo("userD");
        assertThat(round2.getCountersignRecords().get(1).getRoundIndex()).isEqualTo(1);
    }

    // ======================== ADR-0020: 流程进行中 miBody 未完全写入场景 ========================

    /**
     * ADR-0020 核心修复验证：流程进行中，第2轮 miBody 尚未写入 ACT_HI_ACTINST，
     * 返回结构应与已结束状态一致（均按 csRoundIndex 拆分成多条父记录）。
     */
    @Test
    public void testMultiRoundCounterSignWithoutBodyR2() {
        Date startEventTime = new Date(1000);
        Date miBodyR1Start = new Date(2000);
        Date r1Sub1Start = new Date(2100);
        Date r1Sub1End = new Date(2200);
        Date r1Sub2Start = new Date(2300);
        Date r1Sub2End = new Date(2400);
        Date r2Sub1Start = new Date(5000);
        Date r2Sub1End = new Date(5100);
        Date r2Sub2Start = new Date(5200);
        Date r2Sub2End = new Date(5300);

        // 仅第1轮 miBody + 两轮子任务（模拟进行中：第2轮 miBody 尚未写入历史表）
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "multiInstanceBody", "会签审批", miBodyR1Start, miBodyR1Start, null),
                createActivity("csTask", "userTask", "会签审批", r1Sub1Start, r1Sub1Start, "ht-r1-1"),
                createActivity("csTask", "userTask", "会签审批", r1Sub2Start, r1Sub2Start, "ht-r1-2"),
                createActivity("csTask", "userTask", "会签审批", r2Sub1Start, r2Sub1Start, "ht-r2-1"),
                createActivity("csTask", "userTask", "会签审批", r2Sub2Start, r2Sub2Start, "ht-r2-2")
        );

        HistoricTaskInstance htR1_1 = createHistoricTask("ht-r1-1", "csTask", "会签审批", "userA",
                r1Sub1Start, r1Sub1End, "completed");
        HistoricTaskInstance htR1_2 = createHistoricTask("ht-r1-2", "csTask", "会签审批", "userB",
                r1Sub2Start, r1Sub2End, "completed");
        HistoricTaskInstance htR2_1 = createHistoricTask("ht-r2-1", "csTask", "会签审批", "userC",
                r2Sub1Start, r2Sub1End, "completed");
        HistoricTaskInstance htR2_2 = createHistoricTask("ht-r2-2", "csTask", "会签审批", "userD",
                r2Sub2Start, r2Sub2End, "completed");

        Comment cR1_1 = createComment("ht-r1-1", "COUNTER_SIGN_AGREE", "第1轮同意", r1Sub1End);
        Comment cR1_2 = createComment("ht-r1-2", "COUNTER_SIGN_AGREE", "第1轮同意", r1Sub2End);
        Comment cR2_1 = createComment("ht-r2-1", "COUNTER_SIGN_REJECT", "第2轮驳回", r2Sub1End);
        Comment cR2_2 = createComment("ht-r2-2", "COUNTER_SIGN_AGREE", "第2轮同意", r2Sub2End);

        // 第2轮子任务 csRoundIndex=1
        List<HistoricVariableInstance> csRoundIndexVars = Arrays.asList(
                createCsRoundVariable("ht-r2-1", 1),
                createCsRoundVariable("ht-r2-2", 1));
        stubCsRoundIndexVars(csRoundIndexVars);

        stubNormalFlow(activities,
                Arrays.asList(htR1_1, htR1_2, htR2_1, htR2_2),
                Arrays.asList(cR1_1, cR1_2, cR2_1, cR2_2));
        stubBpmnModel(buildMultiInstanceModel());
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        // 核心断言：虽然第2轮 miBody 未写入，仍应正确拆分为 3 条父记录
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        ApprovalRecordVO round1 = result.get(1);
        assertThat(round1.getCountersignRecords()).hasSize(2);
        assertThat(round1.getCountersignRecords().get(0).getRoundIndex()).isEqualTo(0);

        ApprovalRecordVO round2 = result.get(2);
        assertThat(round2.getCountersignRecords()).hasSize(2);
        assertThat(round2.getCountersignRecords().get(0).getRoundIndex()).isEqualTo(1);
    }

    // ======================== 多轮会签：addMultiInstanceExecution 追加审批人（同一 miBody） ========================

    @Test
    public void testAddSignersMultiRoundCounterSign() {
        Date startEventTime = new Date(1000);

        // 同一 miBody 下，第1轮 + 追加的2人
        Date miBodyStart = new Date(2000);
        Date r1Sub1Start = new Date(2100);
        Date r1Sub1End = new Date(2200);
        Date r1Sub2Start = new Date(2300);
        Date r1Sub2End = new Date(2400);
        Date r1Sub3Start = new Date(2500);
        Date r1Sub3End = new Date(2600);

        // 第2轮（addMultiInstanceExecution 追加）：时间更晚
        Date r2Sub1Start = new Date(5000);
        Date r2Sub1End = new Date(5100);
        Date r2Sub2Start = new Date(5200);
        Date r2Sub2End = new Date(5300);

        String miExecId = "exec-mi-1";

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                // 同一 miBody + 5 个会签人（#N 后缀即 loopCounter）
                createActivity("csTask", "multiInstanceBody", "会签审批", miBodyStart, miBodyStart, null, miExecId),
                createActivity("csTask#0", "userTask", "会签审批", r1Sub1Start, r1Sub1Start, "ht-r1-1"),
                createActivity("csTask#1", "userTask", "会签审批", r1Sub2Start, r1Sub2Start, "ht-r1-2"),
                createActivity("csTask#2", "userTask", "会签审批", r1Sub3Start, r1Sub3Start, "ht-r1-3"),
                createActivity("csTask#3", "userTask", "会签审批", r2Sub1Start, r2Sub1Start, "ht-r2-1"),
                createActivity("csTask#4", "userTask", "会签审批", r2Sub2Start, r2Sub2Start, "ht-r2-2")
        );

        HistoricTaskInstance htR1_1 = createHistoricTask("ht-r1-1", "csTask", "会签审批", "userA",
                r1Sub1Start, r1Sub1End, "completed");
        HistoricTaskInstance htR1_2 = createHistoricTask("ht-r1-2", "csTask", "会签审批", "userB",
                r1Sub2Start, r1Sub2End, "completed");
        HistoricTaskInstance htR1_3 = createHistoricTask("ht-r1-3", "csTask", "会签审批", "userC",
                r1Sub3Start, r1Sub3End, "completed");
        HistoricTaskInstance htR2_1 = createHistoricTask("ht-r2-1", "csTask", "会签审批", "userD",
                r2Sub1Start, r2Sub1End, "completed");
        HistoricTaskInstance htR2_2 = createHistoricTask("ht-r2-2", "csTask", "会签审批", "userE",
                r2Sub2Start, r2Sub2End, "completed");

        Comment cR1_1 = createComment("ht-r1-1", "COUNTER_SIGN_AGREE", "第一轮同意A", r1Sub1End);
        Comment cR1_2 = createComment("ht-r1-2", "COUNTER_SIGN_AGREE", "第一轮同意B", r1Sub2End);
        Comment cR1_3 = createComment("ht-r1-3", "COUNTER_SIGN_AGREE", "第一轮同意C", r1Sub3End);
        Comment cR2_1 = createComment("ht-r2-1", "COUNTER_SIGN_AGREE", "第二轮同意D", r2Sub1End);
        Comment cR2_2 = createComment("ht-r2-2", "COUNTER_SIGN_REJECT", "第二轮驳回E", r2Sub2End);

        // 第二轮（addMultiInstanceExecution 追加）的 csRoundIndex=1
        List<HistoricVariableInstance> csRoundIndexVars = Arrays.asList(
                createCsRoundVariable("ht-r2-1", 1),
                createCsRoundVariable("ht-r2-2", 1)
        );

        when(mockIdentityResolver.resolve("userD")).thenReturn("用户D");
        when(mockIdentityResolver.resolve("userE")).thenReturn("用户E");

        stubNormalFlow(activities,
                Arrays.asList(htR1_1, htR1_2, htR1_3, htR2_1, htR2_2),
                Arrays.asList(cR1_1, cR1_2, cR1_3, cR2_1, cR2_2));
        stubBpmnModel(buildMultiInstanceModel());
        stubCsRoundIndexVars(csRoundIndexVars);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        // 应产出 3 条父记录：START + 第1轮会签 + 第2轮会签
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        // 第1轮会签：3 个子记录 (loopCounter 0, 1, 2)
        ApprovalRecordVO round1 = result.get(1);
        assertThat(round1.getNodeId()).isEqualTo("csTask");
        assertThat(round1.getCountersignRecords()).hasSize(3);
        assertThat(round1.getCountersignRecords().get(0).getActorId()).isEqualTo("userA");
        assertThat(round1.getCountersignRecords().get(0).getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(round1.getCountersignRecords().get(1).getActorId()).isEqualTo("userB");
        assertThat(round1.getCountersignRecords().get(2).getActorId()).isEqualTo("userC");

        // 第2轮会签：2 个子记录 (loopCounter 3, 4, 由 addMultiInstanceExecution 追加)
        ApprovalRecordVO round2 = result.get(2);
        assertThat(round2.getNodeId()).isEqualTo("csTask");
        assertThat(round2.getCountersignRecords()).hasSize(2);
        assertThat(round2.getCountersignRecords().get(0).getActorId()).isEqualTo("userD");
        assertThat(round2.getCountersignRecords().get(0).getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(round2.getCountersignRecords().get(0).getComment()).isEqualTo("第二轮同意D");
        assertThat(round2.getCountersignRecords().get(1).getActorId()).isEqualTo("userE");
        assertThat(round2.getCountersignRecords().get(1).getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
        assertThat(round2.getCountersignRecords().get(1).getComment()).isEqualTo("第二轮驳回E");
    }

    // ======================== 显式轮次分组：csRoundIndex Task 局部变量（路径1+2） ========================

    @Test
    public void testExplicitRoundIndexGrouping() {
        Date startEventTime = new Date(1000);

        // 同一 miBody 下，原始3人 + addMultiInstanceExecution 追加2人（第2轮）
        Date miBodyStart = new Date(2000);
        Date r0Sub1Start = new Date(2100);
        Date r0Sub1End = new Date(2200);
        Date r0Sub2Start = new Date(2300);
        Date r0Sub2End = new Date(2400);
        Date r0Sub3Start = new Date(2500);
        Date r0Sub3End = new Date(2600);

        Date r1Sub1Start = new Date(5000);
        Date r1Sub1End = new Date(5100);
        Date r1Sub2Start = new Date(5200);
        Date r1Sub2End = new Date(5300);

        String miExecId = "exec-mi-1";

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("csTask", "multiInstanceBody", "会签审批", miBodyStart, miBodyStart, null, miExecId),
                createActivity("csTask#0", "userTask", "会签审批", r0Sub1Start, r0Sub1Start, "ht-r0-1"),
                createActivity("csTask#1", "userTask", "会签审批", r0Sub2Start, r0Sub2Start, "ht-r0-2"),
                createActivity("csTask#2", "userTask", "会签审批", r0Sub3Start, r0Sub3Start, "ht-r0-3"),
                createActivity("csTask#3", "userTask", "会签审批", r1Sub1Start, r1Sub1Start, "ht-r1-1"),
                createActivity("csTask#4", "userTask", "会签审批", r1Sub2Start, r1Sub2Start, "ht-r1-2")
        );

        HistoricTaskInstance htR0_1 = createHistoricTask("ht-r0-1", "csTask", "会签审批", "userA",
                r0Sub1Start, r0Sub1End, "completed");
        HistoricTaskInstance htR0_2 = createHistoricTask("ht-r0-2", "csTask", "会签审批", "userB",
                r0Sub2Start, r0Sub2End, "completed");
        HistoricTaskInstance htR0_3 = createHistoricTask("ht-r0-3", "csTask", "会签审批", "userC",
                r0Sub3Start, r0Sub3End, "completed");
        HistoricTaskInstance htR1_1 = createHistoricTask("ht-r1-1", "csTask", "会签审批", "userD",
                r1Sub1Start, r1Sub1End, "completed");
        HistoricTaskInstance htR1_2 = createHistoricTask("ht-r1-2", "csTask", "会签审批", "userE",
                r1Sub2Start, r1Sub2End, "completed");

        Comment cR0_1 = createComment("ht-r0-1", "COUNTER_SIGN_AGREE", "第一轮同意A", r0Sub1End);
        Comment cR0_2 = createComment("ht-r0-2", "COUNTER_SIGN_AGREE", "第一轮同意B", r0Sub2End);
        Comment cR0_3 = createComment("ht-r0-3", "COUNTER_SIGN_AGREE", "第一轮同意C", r0Sub3End);
        Comment cR1_1 = createComment("ht-r1-1", "COUNTER_SIGN_AGREE", "第二轮同意D", r1Sub1End);
        Comment cR1_2 = createComment("ht-r1-2", "COUNTER_SIGN_REJECT", "第二轮驳回E", r1Sub2End);

        // csRoundIndex Task 局部变量：第二轮加签任务有显式轮次 = 1
        List<HistoricVariableInstance> csRoundIndexVars = Arrays.asList(
                createCsRoundVariable("ht-r1-1", 1),
                createCsRoundVariable("ht-r1-2", 1)
        );

        when(mockIdentityResolver.resolve("userD")).thenReturn("用户D");
        when(mockIdentityResolver.resolve("userE")).thenReturn("用户E");

        stubNormalFlow(activities,
                Arrays.asList(htR0_1, htR0_2, htR0_3, htR1_1, htR1_2),
                Arrays.asList(cR0_1, cR0_2, cR0_3, cR1_1, cR1_2));
        stubBpmnModel(buildMultiInstanceModel());
        stubCsRoundIndexVars(csRoundIndexVars);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "csTask")).thenReturn(true);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        // 应产出 3 条父记录：START + 第0轮会签 + 第1轮会签
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        // 第0轮（原始审批人，无 csRoundIndex，路径2默认 round=0）
        ApprovalRecordVO round0 = result.get(1);
        assertThat(round0.getNodeId()).isEqualTo("csTask");
        assertThat(round0.getCountersignRecords()).hasSize(3);
        CountersignSubRecord sub0_1 = round0.getCountersignRecords().get(0);
        assertThat(sub0_1.getActorId()).isEqualTo("userA");
        assertThat(sub0_1.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub0_1.getRoundIndex()).isEqualTo(0);
        CountersignSubRecord sub0_2 = round0.getCountersignRecords().get(1);
        assertThat(sub0_2.getActorId()).isEqualTo("userB");
        assertThat(sub0_2.getRoundIndex()).isEqualTo(0);
        CountersignSubRecord sub0_3 = round0.getCountersignRecords().get(2);
        assertThat(sub0_3.getActorId()).isEqualTo("userC");
        assertThat(sub0_3.getRoundIndex()).isEqualTo(0);

        // 第1轮（加签的审批人，csRoundIndex=1，路径1显式值）
        ApprovalRecordVO round1 = result.get(2);
        assertThat(round1.getNodeId()).isEqualTo("csTask");
        assertThat(round1.getCountersignRecords()).hasSize(2);
        CountersignSubRecord sub1_1 = round1.getCountersignRecords().get(0);
        assertThat(sub1_1.getActorId()).isEqualTo("userD");
        assertThat(sub1_1.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(sub1_1.getComment()).isEqualTo("第二轮同意D");
        assertThat(sub1_1.getRoundIndex()).isEqualTo(1);
        CountersignSubRecord sub1_2 = round1.getCountersignRecords().get(1);
        assertThat(sub1_2.getActorId()).isEqualTo("userE");
        assertThat(sub1_2.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
        assertThat(sub1_2.getComment()).isEqualTo("第二轮驳回E");
        assertThat(sub1_2.getRoundIndex()).isEqualTo(1);
    }

    // ======================== 驳回重回：同一节点多次出现 ========================

    @Test
    public void testRejectAndResubmit() {
        Date startEventTime = new Date(1000);
        Date t1FirstStart = new Date(2000);
        Date t1FirstEnd = new Date(3000);
        Date t1SecondStart = new Date(6000);
        Date t1SecondEnd = new Date(7000);

        // 活动实例：startEvent → task1(驳回) → task1(通过)
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", t1FirstStart, t1FirstStart, "ht-1"),
                createActivity("task1", "userTask", "部门审批", t1SecondStart, t1SecondStart, "ht-2")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                t1FirstStart, t1FirstEnd, "deleted");
        HistoricTaskInstance ht2 = createHistoricTask("ht-2", "task1", "部门审批", "user1",
                t1SecondStart, t1SecondEnd, "completed");

        Comment rejectComment = createComment("ht-1", "REJECT", "退回修改", t1FirstEnd);
        Comment agreeComment = createComment("ht-2", "AGREE", "同意通过", t1SecondEnd);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2),
                Arrays.asList(rejectComment, agreeComment));
        stubBpmnModel(buildSimpleModel());

        // task1 不是多实例
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "task1")).thenReturn(false);

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(3); // START + task1驳回 + task1通过

        // START
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);

        // 第一次驳回
        ApprovalRecordVO firstReject = result.get(1);
        assertThat(firstReject.getAction()).isEqualTo(ApprovalAction.REJECT);
        assertThat(firstReject.getNodeId()).isEqualTo("task1");
        assertThat(firstReject.getComment()).isEqualTo("退回修改");

        // 第二次通过（重新提交后再次审批）
        ApprovalRecordVO secondAgree = result.get(2);
        assertThat(secondAgree.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(secondAgree.getNodeId()).isEqualTo("task1");
        assertThat(secondAgree.getComment()).isEqualTo("同意通过");
    }

    // ======================== 活跃节点 ========================

    @Test
    public void testActiveNode() {
        Date startEventTime = new Date(1000);
        Date task1Start = new Date(2000);
        Date task1End = new Date(3000);
        Date task2Start = new Date(4000);

        // 活动实例：task2 还在进行中
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", task1Start, task1Start, "ht-1"),
                createActivity("task2", "userTask", "经理审批", task2Start, null, "ht-2")
        );

        // task1 已完成，task2 还在活跃（无 endTime、无 deleteReason）
        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                task1Start, task1End, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-2", "task2", "经理审批", "user2",
                task2Start, null, null);

        Comment comment1 = createComment("ht-1", "AGREE", "同意", task1End);

        stubNormalFlow(activities, Arrays.asList(ht1, ht2), Collections.singletonList(comment1));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(3);

        // 活跃节点
        ApprovalRecordVO activeRecord = result.get(2);
        assertThat(activeRecord.getAction()).isNull();
        assertThat(activeRecord.getEndTime()).isNull();
        assertThat(activeRecord.getDuration()).isNull();
        assertThat(activeRecord.getNodeId()).isEqualTo("task2");
        assertThat(activeRecord.getActorId()).isEqualTo("user2");
        assertThat(activeRecord.getActorName()).isEqualTo("用户二");
    }

    // ======================== Comment 推断：一级特征提取 ========================

    @Test
    public void testCommentFeatureExtraction() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "审批节点", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "审批节点", "user1",
                taskStart, taskEnd, "completed");

        // 模拟：先有普通留言、后有业务 Comment（验证倒序提取）
        Comment normalMsg = createComment("ht-1", "comment", "麻烦快点审批", new Date(2500));
        Comment businessComment = createComment("ht-1", "REJECT", "申请材料不足，请补充", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Arrays.asList(normalMsg, businessComment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        ApprovalRecordVO taskRecord = result.get(1);
        // 应取业务 Comment（REJECT），而非普通留言
        assertThat(taskRecord.getAction()).isEqualTo(ApprovalAction.REJECT);
        assertThat(taskRecord.getComment()).isEqualTo("申请材料不足，请补充");
    }

    // ======================== Comment 推断：二级 DeleteReason 兜底 ========================

    @Test
    public void testDeleteReasonFallback() {
        Date startEventTime = new Date(1000);
        Date task1Start = new Date(2000);
        Date task1End = new Date(3000);
        Date task2Start = new Date(4000);
        Date task2End = new Date(5000);

        // 任务1: completed → AGREE；任务2: deleted（无 Comment 无法确定具体操作）
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", task1Start, task1Start, "ht-1"),
                createActivity("task2", "userTask", "经理审批", task2Start, task2Start, "ht-2")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                task1Start, task1End, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-2", "task2", "经理审批", "user2",
                task2Start, task2End, "deleted");

        // 无任何 Comment
        stubNormalFlow(activities, Arrays.asList(ht1, ht2), Collections.emptyList());
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(3);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(result.get(2).getAction()).isNull();
    }

    // ======================== 非标准 deleteReason ========================

    @Test
    public void testAbnormalDeleteReason() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "审批节点", taskStart, taskStart, "ht-1")
        );

        // 管理员强杀场景
        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "审批节点", "user1",
                taskStart, taskEnd, "admin-kill");

        stubNormalFlow(activities, Collections.singletonList(ht1), Collections.emptyList());
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.TERMINATE);
    }

    // ======================== 空历史（流程存在但无活动实例） ========================

    @Test
    public void testEmptyHistory() {
        stubProcessInstanceExists();
        // 无活动实例
        stubActivityInstances(Collections.emptyList());
        stubHistoricTaskInstances(Collections.emptyList());
        when(mockTaskService.getProcessInstanceComments(INSTANCE_ID)).thenReturn(Collections.emptyList());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).isEmpty();
    }

    // ======================== 全局排序验证 ========================

    @Test
    public void testGlobalOrdering() {
        Date t1 = new Date(1000);
        Date t2 = new Date(3000);
        Date t3 = new Date(2000); // 中间时间

        // 活动实例故意乱序（activity 查询本身按 startTime ASC 排序）
        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", t1, t1, null),
                createActivity("task2", "userTask", "节点B", t3, t3, "ht-2"),
                createActivity("task1", "userTask", "节点A", t2, t2, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "节点A", "user1",
                t2, t2, "completed");
        HistoricTaskInstance ht2 = createHistoricTask("ht-2", "task2", "节点B", "user2",
                t3, t3, "completed");

        stubNormalFlow(activities, Arrays.asList(ht1, ht2), Collections.emptyList());
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(3);
        // 应按 startTime 升序
        assertThat(result.get(0).getStartTime()).isEqualTo(t1); // START
        assertThat(result.get(1).getStartTime()).isEqualTo(t3); // task2 实际更早开始
        assertThat(result.get(2).getStartTime()).isEqualTo(t2); // task1
    }

    // ======================== 撤回操作 ========================

    @Test
    public void testWithdrawAction() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                taskStart, taskEnd, "deleted");

        Comment withdrawComment = createComment("ht-1", "WITHDRAW", "撤回修改", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Collections.singletonList(withdrawComment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.WITHDRAW);
        assertThat(result.get(1).getComment()).isEqualTo("撤回修改");
    }

    // ======================== 撤销操作 ========================

    @Test
    public void testRevokeAction() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                taskStart, taskEnd, "deleted");

        Comment revokeComment = createComment("ht-1", "INVALID", "申请人作废流程", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Collections.singletonList(revokeComment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.INVALID);
    }

    // ======================== 转办操作 ========================

    @Test
    public void testTransferAction() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "部门审批", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "部门审批", "user1",
                taskStart, taskEnd, "completed");

        Comment transferComment = createComment("ht-1", "TRANSFER", "转办给张三", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Collections.singletonList(transferComment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.TRANSFER);
    }

    // ======================== 调用交互验证 ========================

    @Test
    public void testDelegationToHistoryWorkflow() {
        Date startEventTime = new Date(1000);
        Date taskStart = new Date(2000);
        Date taskEnd = new Date(3000);

        List<HistoricActivityInstance> activities = Arrays.asList(
                createActivity("start", "startEvent", "开始", startEventTime, startEventTime, null),
                createActivity("task1", "userTask", "审批", taskStart, taskStart, "ht-1")
        );

        HistoricTaskInstance ht1 = createHistoricTask("ht-1", "task1", "审批", "user1",
                taskStart, taskEnd, "completed");

        Comment comment = createComment("ht-1", "AGREE", "通过", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1), Collections.singletonList(comment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAction()).isEqualTo(ApprovalAction.START);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.AGREE);
    }

    // ======================== Test Helpers ========================

    private HistoricActivityInstance createActivity(String activityId, String activityType,
                                                     String activityName, Date startTime,
                                                     Date endTime, String taskId) {
        return createActivity(activityId, activityType, activityName, startTime, endTime, taskId, null);
    }

    private HistoricActivityInstance createActivity(String activityId, String activityType,
                                                     String activityName, Date startTime,
                                                     Date endTime, String taskId,
                                                     String executionId) {
        HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
        when(activity.getActivityId()).thenReturn(activityId);
        when(activity.getActivityType()).thenReturn(activityType);
        when(activity.getActivityName()).thenReturn(activityName);
        when(activity.getStartTime()).thenReturn(startTime);
        when(activity.getEndTime()).thenReturn(endTime);
        when(activity.getTaskId()).thenReturn(taskId);
        when(activity.getProcessDefinitionId()).thenReturn(PROCESS_DEF_ID);
        when(activity.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        if (executionId != null) {
            when(activity.getExecutionId()).thenReturn(executionId);
        }
        return activity;
    }

    private HistoricTaskInstance createHistoricTask(String taskId, String taskDefKey,
                                                     String taskName, String assignee,
                                                     Date createTime, Date endTime,
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

    private Comment createComment(String taskId, String type, String fullMessage, Date time) {
        Comment comment = mock(Comment.class);
        when(comment.getTaskId()).thenReturn(taskId);
        when(comment.getType()).thenReturn(type);
        when(comment.getFullMessage()).thenReturn(fullMessage);
        when(comment.getTime()).thenReturn(time);
        return comment;
    }

    private BpmnModel buildSimpleModel() {
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

        UserTask task2 = new UserTask();
        task2.setId("task2");
        process.addFlowElement(task2);
        addFlow(process, "f2", "task1", "task2");

        return model;
    }

    private BpmnModel buildMultiInstanceModel() {
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

    private void addFlow(Process process, String id, String source, String target) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(source);
        flow.setTargetRef(target);
        process.addFlowElement(flow);
    }

    /**
     * 创建一个有 taskId 的 csRoundIndex 变量，用于 stubCsRoundIndexVars。
     */
    private HistoricVariableInstance createCsRoundVariable(String taskId, int roundIndex) {
        HistoricVariableInstance var = mock(HistoricVariableInstance.class);
        when(var.getTaskId()).thenReturn(taskId);
        when(var.getValue()).thenReturn(roundIndex);
        return var;
    }

    /**
     * 设置 csRoundIndex 查询的返回值。
     */
    private void stubCsRoundIndexVars(List<HistoricVariableInstance> vars) {
        csRoundIndexVarData = vars != null ? vars : Collections.emptyList();
    }

    // ======================== Mock Stubs ========================

    private void stubProcessInstanceNotFound() {
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId(INSTANCE_ID)).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(null);
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

    private void stubActivityInstances(List<HistoricActivityInstance> activities) {
        HistoricActivityInstanceQuery activityQuery = mock(HistoricActivityInstanceQuery.class);
        when(mockHistoryService.createHistoricActivityInstanceQuery()).thenReturn(activityQuery);
        when(activityQuery.processInstanceId(INSTANCE_ID)).thenReturn(activityQuery);
        when(activityQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(activityQuery);
        when(activityQuery.asc()).thenReturn(activityQuery);
        when(activityQuery.list()).thenReturn(activities);
    }

    private void stubHistoricTaskInstances(List<HistoricTaskInstance> tasks) {
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(INSTANCE_ID)).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceStartTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.asc()).thenReturn(histTaskQuery);
        when(histTaskQuery.list()).thenReturn(tasks != null ? tasks : Collections.emptyList());
    }

    private void stubNormalFlow(List<HistoricActivityInstance> activities,
                                 List<HistoricTaskInstance> historicTasks,
                                 List<Comment> comments) {
        stubProcessInstanceExists();
        stubActivityInstances(activities);
        stubHistoricTaskInstances(historicTasks);
        when(mockTaskService.getProcessInstanceComments(INSTANCE_ID))
                .thenReturn(comments != null ? comments : Collections.emptyList());
    }

    private void stubBpmnModel(BpmnModel model) {
        when(mockBpmnModelCache.getBpmnModel(PROCESS_DEF_ID)).thenReturn(model);
    }
}
