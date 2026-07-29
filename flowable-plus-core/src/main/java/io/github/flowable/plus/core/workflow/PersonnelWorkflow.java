package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.vo.ApprovalPersonnelVO;
import io.github.flowable.plus.core.vo.PersonnelInfo;
import io.github.flowable.plus.core.vo.UserInfo;
import io.github.flowable.plus.core.spi.UserInfoResolver;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批人员查询工作流模块，封装已审批/未审批人员的分组查询和用户信息补全。
 *
 * <p>复用 {@link ProcessQueryWorkflow} 相同的 Flowable 查询模式
 * （TaskService + HistoryService），不引入新查询路径。</p>
 *
 * @author flowable-plus
 * @since 1.1
 */
public class PersonnelWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PersonnelWorkflow.class);

    private final TaskService taskService;
    private final HistoryService historyService;
    private final UserInfoResolver userInfoResolver;

    /**
     * @param taskService      Flowable 任务服务，不可为 null
     * @param historyService   Flowable 历史服务，不可为 null
     * @param userInfoResolver 用户信息解析器，可为 null（null 时人员信息字段不补全）
     */
    public PersonnelWorkflow(TaskService taskService,
                             HistoryService historyService,
                             UserInfoResolver userInfoResolver) {
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        this.taskService = taskService;
        this.historyService = historyService;
        this.userInfoResolver = userInfoResolver;
    }

    /**
     * 获取流程实例的审批人员分组（已审批/未审批）。
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return 审批人员分组，流程实例不存在时返回空列表
     * @throws IllegalArgumentException 如果 processInstanceId 为 null 或空
     */
    public ApprovalPersonnelVO getApprovalPersonnel(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 查询活跃运行时任务（未审批）
        List<Task> activeTaskObjs = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();

        // 2. 查询已完成历史任务（已审批）
        List<HistoricTaskInstance> historicTaskObjs = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().asc()
                .list();

        // 3. 若都为空，验证流程实例是否存在
        if (activeTaskObjs.isEmpty() && historicTaskObjs.isEmpty()) {
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (hpi == null) {
                log.warn("getApprovalPersonnel: 流程实例 {} 不存在", processInstanceId);
            }
            return ApprovalPersonnelVO.builder()
                    .approved(Collections.emptyList())
                    .pending(Collections.emptyList())
                    .build();
        }

        // 4. 构建已审批人员列表（去重，按审批时间升序）
        Map<String, HistoricTaskInstance> approvedMap = new LinkedHashMap<>();
        for (HistoricTaskInstance hti : historicTaskObjs) {
            String assignee = hti.getAssignee();
            if (assignee != null && !assignee.isEmpty()) {
                approvedMap.putIfAbsent(assignee, hti);
            }
        }
        List<PersonnelInfo> approved = approvedMap.values().stream()
                .map(hti -> PersonnelInfo.builder()
                        .userId(hti.getAssignee())
                        .taskId(null)
                        .approvalTime(hti.getEndTime())
                        .build())
                .collect(Collectors.toList());

        // 5. 构建未审批人员列表（去重，按任务创建时间升序）
        Map<String, Task> pendingMap = new LinkedHashMap<>();
        for (Task task : activeTaskObjs) {
            String assignee = task.getAssignee();
            if (assignee != null && !assignee.isEmpty()) {
                pendingMap.putIfAbsent(assignee, task);
            }
        }
        List<PersonnelInfo> pending = pendingMap.values().stream()
                .sorted(Comparator.comparing(Task::getCreateTime))
                .map(task -> PersonnelInfo.builder()
                        .userId(task.getAssignee())
                        .taskId(task.getId())
                        .approvalTime(null)
                        .build())
                .collect(Collectors.toList());

        // 6. 用户信息补全
        if (userInfoResolver != null) {
            Set<String> allUserIds = new LinkedHashSet<>();
            for (PersonnelInfo pi : approved) {
                allUserIds.add(pi.getUserId());
            }
            for (PersonnelInfo pi : pending) {
                allUserIds.add(pi.getUserId());
            }

            if (!allUserIds.isEmpty()) {
                Map<String, UserInfo> userInfoMap = userInfoResolver.resolveBatch(allUserIds);
                if (userInfoMap != null) {
                    fillUserInfo(approved, userInfoMap);
                    fillUserInfo(pending, userInfoMap);
                }
            }
        }

        return ApprovalPersonnelVO.builder()
                .approved(approved)
                .pending(pending)
                .build();
    }

    private void fillUserInfo(List<PersonnelInfo> list, Map<String, UserInfo> userInfoMap) {
        for (PersonnelInfo pi : list) {
            UserInfo info = userInfoMap.get(pi.getUserId());
            if (info != null) {
                pi.setNickName(info.getNickName());
                pi.setDeptId(info.getDeptId());
                pi.setDeptName(info.getDeptName());
            }
        }
    }
}
