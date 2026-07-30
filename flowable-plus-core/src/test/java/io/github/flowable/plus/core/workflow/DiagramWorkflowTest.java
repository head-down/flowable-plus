package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.DiagramStatesVO;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DiagramWorkflow 单元测试。
 *
 * <p>覆盖 BPMN XML 获取和节点状态查询，无外部依赖。
 *
 * @author flowable-plus
 */
public class DiagramWorkflowTest {

    private HistoryService mockHistoryService;
    private RepositoryService mockRepositoryService;
    private TaskService mockTaskService;

    private static final String TEST_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "targetNamespace=\"http://flowable.org/test\">"
            + "<process id=\"testProcess\" name=\"测试流程\"/>"
            + "</definitions>";

    @BeforeEach
    void setUp() {
        mockHistoryService = mock(HistoryService.class);
        mockRepositoryService = mock(RepositoryService.class);
        mockTaskService = mock(TaskService.class);
    }

    // ======================== getProcessDiagramXml ========================

    @Test
    void getProcessDiagramXmlShouldThrowForNullId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramXml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    void getProcessDiagramXmlShouldThrowForEmptyId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramXml(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    void getProcessDiagramXmlShouldThrowWhenProcessDefinitionNotFound() {
        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(pdQuery.processDefinitionId("pd-001")).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(null);
        when(mockRepositoryService.createProcessDefinitionQuery()).thenReturn(pdQuery);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramXml("pd-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("未找到流程定义");
    }

    @Test
    void getProcessDiagramXmlShouldReturnXmlForValidDefinition() {
        ProcessDefinition pd = mock(ProcessDefinition.class);
        when(pd.getDeploymentId()).thenReturn("deploy-001");
        when(pd.getResourceName()).thenReturn("test.bpmn20.xml");

        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(pdQuery.processDefinitionId("pd-001")).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(pd);
        when(mockRepositoryService.createProcessDefinitionQuery()).thenReturn(pdQuery);

        InputStream xmlStream = new ByteArrayInputStream(TEST_XML.getBytes(StandardCharsets.UTF_8));
        when(mockRepositoryService.getResourceAsStream("deploy-001", "test.bpmn20.xml"))
                .thenReturn(xmlStream);

        DiagramWorkflow dw = createWorkflow();
        ProcessDiagramVO result = dw.getProcessDiagramXml("pd-001");

        assertThat(result).isNotNull();
        assertThat(result.getProcessDefinitionId()).isEqualTo("pd-001");
        assertThat(result.getXml()).isEqualTo(TEST_XML);
    }

    @Test
    void getProcessDiagramXmlShouldThrowWhenResourceStreamIsNull() {
        ProcessDefinition pd = mock(ProcessDefinition.class);
        when(pd.getDeploymentId()).thenReturn("deploy-001");
        when(pd.getResourceName()).thenReturn("test.bpmn20.xml");

        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(pdQuery.processDefinitionId("pd-001")).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(pd);
        when(mockRepositoryService.createProcessDefinitionQuery()).thenReturn(pdQuery);

        when(mockRepositoryService.getResourceAsStream("deploy-001", "test.bpmn20.xml"))
                .thenReturn(null);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramXml("pd-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("BPMN XML 资源");
    }

    // ======================== getProcessDiagramStates 异常 ========================

    @Test
    void getProcessDiagramStatesShouldThrowForNullId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramStates(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void getProcessDiagramStatesShouldThrowForEmptyId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramStates(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void getProcessDiagramStatesShouldThrowWhenProcessInstanceNotFound() {
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(null);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagramStates("pi-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程实例");
    }

    // ======================== getProcessDiagramStates: 节点状态分类 ========================

    @Test
    void getProcessDiagramStatesShouldClassifyActiveNodes() {
        String piId = "pi-active";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstance activeAct = activityInstance("ut1", "userTask");
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.singletonList(activeAct));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.singletonList(activeAct));

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result).isNotNull();
        assertThat(result.getStates().getActive()).containsExactly("ut1");
        assertThat(result.getStates().getCompleted()).isEmpty();
        assertThat(result.getStates().getAuto()).isEmpty();
    }

    @Test
    void getProcessDiagramStatesShouldClassifyCompletedUserTasks() {
        String piId = "pi-completed";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstance completedUt = activityInstance("ut1", "userTask");
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.singletonList(completedUt));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getStates().getCompleted()).containsExactly("ut1");
        assertThat(result.getStates().getActive()).isEmpty();
    }

    @Test
    void getProcessDiagramStatesShouldClassifyServiceTaskAsAuto() {
        String piId = "pi-auto";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstance serviceTask = activityInstance("st1", "serviceTask");
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.singletonList(serviceTask));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getStates().getAuto()).containsExactly("st1");
        assertThat(result.getStates().getCompleted()).isEmpty();
    }

    @Test
    void getProcessDiagramStatesShouldSkipGatewayAndEventTypes() {
        String piId = "pi-skips";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        List<HistoricActivityInstance> allActs = Arrays.asList(
                activityInstance("start", "startEvent"),
                activityInstance("end", "endEvent"),
                activityInstance("gw_ex", "exclusiveGateway"),
                activityInstance("gw_para", "parallelGateway"),
                activityInstance("gw_inc", "inclusiveGateway"),
                activityInstance("boundary", "boundaryEvent"),
                activityInstance("catch", "intermediateCatchEvent"),
                activityInstance("throw_evt", "intermediateThrowEvent"),
                activityInstance("eb_gw", "eventBasedGateway")
        );
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(allActs);

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        // SKIP_TYPES 中的节点不应出现在任何分类中
        assertThat(result.getStates().getActive()).isEmpty();
        assertThat(result.getStates().getCompleted()).isEmpty();
        assertThat(result.getStates().getAuto()).isEmpty();
    }

    @Test
    void getProcessDiagramStatesShouldPrioritizeActiveOverCompleted() {
        String piId = "pi-active-priority";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstance act = activityInstance("ut1", "userTask");
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.singletonList(act));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.singletonList(act));

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getStates().getActive()).containsExactly("ut1");
        assertThat(result.getStates().getCompleted()).isEmpty();
    }

    @Test
    void getProcessDiagramStatesShouldDeduplicateSameNode() {
        String piId = "pi-dedup";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstance first = activityInstance("ut1", "userTask");
        HistoricActivityInstance second = activityInstance("ut1", "userTask");
        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Arrays.asList(first, second));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        // 去重后只有一个节点
        assertThat(result.getStates().getCompleted()).hasSize(1);
    }

    // ======================== getProcessDiagramStates: 连线 ========================

    @Test
    void getProcessDiagramStatesShouldReturnCompletedFlows() {
        String piId = "pi-flows";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstance flow1 = activityInstance("flow1", "sequenceFlow");
        HistoricActivityInstance flow2 = activityInstance("flow2", "sequenceFlow");
        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Arrays.asList(flow1, flow2));

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getCompletedFlows()).containsExactly("flow1", "flow2");
    }

    @Test
    void getProcessDiagramStatesShouldReturnEmptyFlowsWhenNone() {
        String piId = "pi-no-flows";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getCompletedFlows()).isEmpty();
    }

    // ======================== getProcessDiagramStates: 活跃任务 ========================

    @Test
    void getProcessDiagramStatesShouldReturnActiveTasks() {
        String piId = "pi-tasks";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        Date now = new Date();
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-001");
        when(task.getTaskDefinitionKey()).thenReturn("approvalTask");
        when(task.getName()).thenReturn("审批");
        when(task.getAssignee()).thenReturn("张三");
        doReturn(Collections.emptyList()).when(task).getIdentityLinks();
        when(task.getCreateTime()).thenReturn(now);
        when(task.getDueDate()).thenReturn(null);
        when(task.isSuspended()).thenReturn(false);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(task));

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getActiveTasks()).hasSize(1);
        DiagramStatesVO.TaskBriefVO taskVo = result.getActiveTasks().get(0);
        assertThat(taskVo.getTaskId()).isEqualTo("task-001");
        assertThat(taskVo.getActivityId()).isEqualTo("approvalTask");
        assertThat(taskVo.getTaskName()).isEqualTo("审批");
        assertThat(taskVo.getAssignee()).isEqualTo("张三");
        assertThat(taskVo.getSuspensionState()).isEqualTo(1);
        assertThat(taskVo.getDueDate()).isNull();
    }

    @Test
    void getProcessDiagramStatesShouldReturnTaskWithDueDate() {
        String piId = "pi-due";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        Date dueDate = new Date();
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-002");
        when(task.getTaskDefinitionKey()).thenReturn("node1");
        when(task.getName()).thenReturn("紧急审批");
        when(task.getAssignee()).thenReturn("李四");
        doReturn(Collections.emptyList()).when(task).getIdentityLinks();
        when(task.getCreateTime()).thenReturn(new Date());
        when(task.getDueDate()).thenReturn(dueDate);
        when(task.isSuspended()).thenReturn(false);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(task));

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getActiveTasks()).hasSize(1);
        assertThat(result.getActiveTasks().get(0).getDueDate()).isNotNull();
    }

    @Test
    void getProcessDiagramStatesShouldReturnTaskWithCandidateGroups() {
        String piId = "pi-candidates";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        IdentityLinkInfo link1 = mock(IdentityLinkInfo.class);
        when(link1.getType()).thenReturn("candidate");
        when(link1.getGroupId()).thenReturn("pm");
        IdentityLinkInfo link2 = mock(IdentityLinkInfo.class);
        when(link2.getType()).thenReturn("candidate");
        when(link2.getGroupId()).thenReturn("manager");
        IdentityLinkInfo link3 = mock(IdentityLinkInfo.class);
        when(link3.getType()).thenReturn("assignee");
        when(link3.getGroupId()).thenReturn(null);

        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-003");
        when(task.getTaskDefinitionKey()).thenReturn("node1");
        when(task.getName()).thenReturn("会签");
        when(task.getAssignee()).thenReturn(null);
        doReturn(Arrays.asList(link1, link2, link3)).when(task).getIdentityLinks();
        when(task.getCreateTime()).thenReturn(new Date());
        when(task.getDueDate()).thenReturn(null);
        when(task.isSuspended()).thenReturn(false);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(task));

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getActiveTasks()).hasSize(1);
        assertThat(result.getActiveTasks().get(0).getCandidateGroups())
                .containsExactlyInAnyOrder("pm", "manager");
    }

    @Test
    void getProcessDiagramStatesShouldReturnSuspendedTask() {
        String piId = "pi-suspended";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-004");
        when(task.getTaskDefinitionKey()).thenReturn("node1");
        when(task.getName()).thenReturn("已挂起任务");
        when(task.getAssignee()).thenReturn("王五");
        doReturn(Collections.emptyList()).when(task).getIdentityLinks();
        when(task.getCreateTime()).thenReturn(new Date());
        when(task.getDueDate()).thenReturn(null);
        when(task.isSuspended()).thenReturn(true); // suspended

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(task));

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getActiveTasks()).hasSize(1);
        assertThat(result.getActiveTasks().get(0).getSuspensionState()).isEqualTo(2);
    }

    // ======================== 构造器验证 ========================

    @Test
    void constructorShouldThrowWhenHistoryServiceIsNull() {
        assertThatThrownBy(() -> new DiagramWorkflow(null, mockRepositoryService, mockTaskService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService");
    }

    @Test
    void constructorShouldThrowWhenRepositoryServiceIsNull() {
        assertThatThrownBy(() -> new DiagramWorkflow(mockHistoryService, null, mockTaskService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RepositoryService");
    }

    @Test
    void constructorShouldThrowWhenTaskServiceIsNull() {
        assertThatThrownBy(() -> new DiagramWorkflow(mockHistoryService, mockRepositoryService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService");
    }

    // ======================== processInstanceId 校验 ========================

    @Test
    void processInstanceIdShouldBePropagatedToResponse() {
        String piId = "pi-response";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        HistoricActivityInstanceQuery allQuery = mock(HistoricActivityInstanceQuery.class);
        when(allQuery.processInstanceId(piId)).thenReturn(allQuery);
        when(allQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(allQuery);
        when(allQuery.asc()).thenReturn(allQuery);
        when(allQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery flowQuery = mock(HistoricActivityInstanceQuery.class);
        when(flowQuery.processInstanceId(piId)).thenReturn(flowQuery);
        when(flowQuery.activityType("sequenceFlow")).thenReturn(flowQuery);
        when(flowQuery.finished()).thenReturn(flowQuery);
        when(flowQuery.list()).thenReturn(Collections.emptyList());

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId(piId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(allQuery, activeQuery, flowQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        DiagramWorkflow dw = createWorkflow();
        DiagramStatesVO result = dw.getProcessDiagramStates(piId);

        assertThat(result.getProcessInstanceId()).isEqualTo(piId);
    }

    // ======================== 辅助方法 ========================

    private DiagramWorkflow createWorkflow() {
        return new DiagramWorkflow(mockHistoryService, mockRepositoryService, mockTaskService);
    }

    private static HistoricActivityInstance activityInstance(String activityId, String activityType) {
        HistoricActivityInstance act = mock(HistoricActivityInstance.class);
        when(act.getActivityId()).thenReturn(activityId);
        when(act.getActivityType()).thenReturn(activityType);
        return act;
    }
}
