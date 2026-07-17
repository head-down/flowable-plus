package io.github.flowable.plus.core;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        historyWorkflow = new HistoryWorkflow(mockHistoryService, mockTaskService,
                mockBpmnModelCache, mockMultiInstanceDetector, mockIdentityResolver);
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

        Comment revokeComment = createComment("ht-1", "REVOKE", "申请人撤销流程", taskEnd);

        stubNormalFlow(activities, Collections.singletonList(ht1),
                Collections.singletonList(revokeComment));
        stubBpmnModel(buildSimpleModel());

        List<ApprovalRecordVO> result = historyWorkflow.getApprovalHistory(INSTANCE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getAction()).isEqualTo(ApprovalAction.REVOKE);
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
        HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
        when(activity.getActivityId()).thenReturn(activityId);
        when(activity.getActivityType()).thenReturn(activityType);
        when(activity.getActivityName()).thenReturn(activityName);
        when(activity.getStartTime()).thenReturn(startTime);
        when(activity.getEndTime()).thenReturn(endTime);
        when(activity.getTaskId()).thenReturn(taskId);
        when(activity.getProcessDefinitionId()).thenReturn(PROCESS_DEF_ID);
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
