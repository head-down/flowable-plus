package io.github.flowable.plus.core.enums;

import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApprovalAction 枚举 + CommentTypeConverter 映射 + VO Builder 测试。
 */
public class ApprovalActionTest {

    // ======================== ApprovalAction 枚举值覆盖 ========================

    @Test
    void testAllValuesPresent() {
        assertThat(ApprovalAction.values())
                .containsExactly(
                        ApprovalAction.START,
                        ApprovalAction.AGREE,
                        ApprovalAction.REJECT,
                        ApprovalAction.WITHDRAW,
                        ApprovalAction.REVOKE,
                        ApprovalAction.COUNTER_SIGN_AGREE,
                        ApprovalAction.COUNTER_SIGN_REJECT,
                        ApprovalAction.TRANSFER,
                        ApprovalAction.ADD_SIGN,
                        ApprovalAction.DELETE_SIGN,
                        ApprovalAction.TERMINATE
                );
    }

    @Test
    void testValueCountIsEleven() {
        assertThat(ApprovalAction.values()).hasSize(11);
    }

    // ======================== CommentTypeConverter 一对一映射 ========================

    @Test
    void testFromCommentTypeAgree() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.AGREE))
                .isEqualTo(ApprovalAction.AGREE);
    }

    @Test
    void testFromCommentTypeReject() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.REJECT))
                .isEqualTo(ApprovalAction.REJECT);
    }

    @Test
    void testFromCommentTypeWithdraw() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.WITHDRAW))
                .isEqualTo(ApprovalAction.WITHDRAW);
    }

    @Test
    void testFromCommentTypeRevoke() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.REVOKE))
                .isEqualTo(ApprovalAction.REVOKE);
    }

    @Test
    void testFromCommentTypeCounterSignAgree() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.COUNTER_SIGN_AGREE))
                .isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
    }

    @Test
    void testFromCommentTypeCounterSignReject() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.COUNTER_SIGN_REJECT))
                .isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
    }

    @Test
    void testFromCommentTypeAddSign() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.ADD_SIGN))
                .isEqualTo(ApprovalAction.ADD_SIGN);
    }

    @Test
    void testFromCommentTypeDeleteSign() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.DELETE_SIGN))
                .isEqualTo(ApprovalAction.DELETE_SIGN);
    }

    @Test
    void testFromCommentTypeTransfer() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.TRANSFER))
                .isEqualTo(ApprovalAction.TRANSFER);
    }

    // ======================== CommentTypeConverter 语义映射 ========================

    @Test
    void testFromCommentTypeReturnMapsToAgree() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.RETURN))
                .isEqualTo(ApprovalAction.AGREE);
    }

    @Test
    void testFromCommentTypeAutoCompleteMapsToAgree() {
        assertThat(CommentTypeConverter.toApprovalAction(CommentType.AUTO_COMPLETE))
                .isEqualTo(ApprovalAction.AGREE);
    }

    // ======================== CommentTypeConverter 异常路径 ========================

    @Test
    void testFromCommentTypeNullThrows() {
        assertThatThrownBy(() -> CommentTypeConverter.toApprovalAction(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void testFromCommentTypeDelegateThrows() {
        assertThatThrownBy(() -> CommentTypeConverter.toApprovalAction(CommentType.DELEGATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELEGATE");
    }

    @Test
    void testFromCommentTypeResolveDelegateThrows() {
        assertThatThrownBy(() -> CommentTypeConverter.toApprovalAction(CommentType.RESOLVE_DELEGATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESOLVE_DELEGATE");
    }

    // ======================== CommentTypeConverter 不可实例化 ========================

    @Test
    void testCannotInstantiate() {
        assertThatThrownBy(() -> {
            java.lang.reflect.Constructor<?> ctor = CommentTypeConverter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        }).hasRootCauseExactlyInstanceOf(UnsupportedOperationException.class);
    }

    // ======================== ApprovalRecordVO Builder ========================

    @Test
    void testApprovalRecordVOBuilderFull() {
        Date start = new Date(1000);
        Date end = new Date(2000);

        ApprovalRecordVO record = ApprovalRecordVO.builder()
                .taskId("task-001")
                .nodeId("node-approval")
                .nodeName("部门经理审批")
                .action(ApprovalAction.AGREE)
                .actorId("user-zhangsan")
                .actorName("张三")
                .comment("同意")
                .startTime(start)
                .endTime(end)
                .duration(1000L)
                .countersignRecords(null)
                .build();

        assertThat(record.getTaskId()).isEqualTo("task-001");
        assertThat(record.getNodeId()).isEqualTo("node-approval");
        assertThat(record.getNodeName()).isEqualTo("部门经理审批");
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        assertThat(record.getActorId()).isEqualTo("user-zhangsan");
        assertThat(record.getActorName()).isEqualTo("张三");
        assertThat(record.getComment()).isEqualTo("同意");
        assertThat(record.getStartTime()).isEqualTo(start);
        assertThat(record.getEndTime()).isEqualTo(end);
        assertThat(record.getDuration()).isEqualTo(1000L);
        assertThat(record.getCountersignRecords()).isNull();
    }

    @Test
    void testApprovalRecordVOBuilderMinimal() {
        ApprovalRecordVO record = ApprovalRecordVO.builder()
                .taskId("task-001")
                .nodeId("node-approval")
                .action(ApprovalAction.AGREE)
                .build();

        assertThat(record.getTaskId()).isEqualTo("task-001");
        assertThat(record.getNodeId()).isEqualTo("node-approval");
        assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE);
        // 未设置的字段应为 null
        assertThat(record.getNodeName()).isNull();
        assertThat(record.getActorId()).isNull();
        assertThat(record.getActorName()).isNull();
        assertThat(record.getComment()).isNull();
        assertThat(record.getStartTime()).isNull();
        assertThat(record.getEndTime()).isNull();
        assertThat(record.getDuration()).isNull();
        assertThat(record.getCountersignRecords()).isNull();
    }

    @Test
    void testApprovalRecordVONoArgsConstructor() {
        ApprovalRecordVO record = new ApprovalRecordVO();
        assertThat(record.getTaskId()).isNull();
    }

    @Test
    void testApprovalRecordVOAllArgsConstructor() {
        Date start = new Date(1000);
        Date end = new Date(2000);

        ApprovalRecordVO record = new ApprovalRecordVO(
                "task-001", "node-approval", "审批节点",
                ApprovalAction.REJECT, "actor-id", "actor-name",
                "不同意", start, end, 1000L, null
        );

        assertThat(record.getTaskId()).isEqualTo("task-001");
        assertThat(record.getAction()).isEqualTo(ApprovalAction.REJECT);
    }

    // ======================== CountersignSubRecord Builder ========================

    @Test
    void testCountersignSubRecordBuilderFull() {
        Date start = new Date(1000);
        Date end = new Date(2000);

        CountersignSubRecord record = CountersignSubRecord.builder()
                .taskId("task-cs-001")
                .nodeId("node-countersign")
                .nodeName("部门会签")
                .action(ApprovalAction.COUNTER_SIGN_AGREE)
                .actorId("user-lisi")
                .actorName("李四")
                .comment("无意见")
                .startTime(start)
                .endTime(end)
                .duration(1000L)
                .build();

        assertThat(record.getTaskId()).isEqualTo("task-cs-001");
        assertThat(record.getNodeId()).isEqualTo("node-countersign");
        assertThat(record.getNodeName()).isEqualTo("部门会签");
        assertThat(record.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
        assertThat(record.getActorId()).isEqualTo("user-lisi");
        assertThat(record.getActorName()).isEqualTo("李四");
        assertThat(record.getComment()).isEqualTo("无意见");
        assertThat(record.getStartTime()).isEqualTo(start);
        assertThat(record.getEndTime()).isEqualTo(end);
        assertThat(record.getDuration()).isEqualTo(1000L);
    }

    @Test
    void testCountersignSubRecordNoArgsConstructor() {
        CountersignSubRecord record = new CountersignSubRecord();
        assertThat(record.getTaskId()).isNull();
    }

    @Test
    void testCountersignSubRecordAllArgsConstructor() {
        Date start = new Date(1000);
        Date end = new Date(2000);

        CountersignSubRecord record = new CountersignSubRecord(
                "task-cs-001", "node-countersign", "部门会签",
                ApprovalAction.COUNTER_SIGN_REJECT, "actor-id", "actor-name",
                "有异议", start, end, 1000L
        );

        assertThat(record.getTaskId()).isEqualTo("task-cs-001");
        assertThat(record.getAction()).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
    }
}
