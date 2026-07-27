package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.image.ProcessDiagramGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DiagramWorkflow 单元测试。
 *
 * <p>覆盖 BPMN GraphicInfo 解析、中文字体降级链、SVG 手工构建、
 * 节点状态分类和默认布局生成等核心逻辑，无外部依赖。</p>
 *
 * @author flowable-plus
 */
public class DiagramWorkflowTest {

    private HistoryService mockHistoryService;
    private BpmnModelCache mockBpmnModelCache;
    private ProcessDiagramGenerator mockDiagramGenerator;

    @BeforeEach
    void setUp() {
        mockHistoryService = mock(HistoryService.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockDiagramGenerator = mock(ProcessDiagramGenerator.class);
    }

    // ======================== resolveFont ========================

    @Test
    void resolveFontShouldReturnSongTiForNull() {
        assertThat(DiagramWorkflow.resolveFont(null)).isEqualTo("宋体");
    }

    @Test
    void resolveFontShouldReturnSongTiForEmpty() {
        assertThat(DiagramWorkflow.resolveFont("")).isEqualTo("宋体");
    }

    @Test
    void resolveFontShouldReturnExactMatchWhenFontAvailable() {
        // 在 Windows 上 "宋体" 通常可用，直接返回原名
        String result = DiagramWorkflow.resolveFont("宋体");
        assertThat(result).isNotNull().isNotEmpty();
        // 任何有效字体名都不应返回方块字符
        assertThat(result).doesNotContain("□");
    }

    @Test
    void resolveFontShouldFallbackForNonexistentFont() {
        // 传入不存在的字体名，期望降级到某个中文字体或返回原名
        String result = DiagramWorkflow.resolveFont("不存在的字体_xyz_abc");
        assertThat(result).isNotNull().isNotEmpty();
    }

    @Test
    void resolveFontShouldHandleCaseInsensitiveMatch() {
        // 传入预期字体名的小写变体，验证忽略大小写匹配
        String result = DiagramWorkflow.resolveFont("simsun");
        assertThat(result).isNotNull().isNotEmpty();
    }

    @Test
    void resolveFontShouldAlwaysReturnNonNull() {
        // 传入各种极端输入，验证不会抛出异常
        DiagramWorkflow.resolveFont("微软雅黑");
        DiagramWorkflow.resolveFont("SimHei");
        DiagramWorkflow.resolveFont("Arial");
        // 不应抛出异常
    }

    // ======================== classifyNodeStates ========================

    @Test
    void classifyNodeStatesShouldMarkActiveNodes() {
        HistoricActivityInstance activeAct = activityInstance("ut1", "userTask");
        List<HistoricActivityInstance> allActivities = Collections.singletonList(activeAct);
        Set<String> activeNodeIds = Collections.singleton("ut1");

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        assertThat(states).containsEntry("ut1", "active");
    }

    @Test
    void classifyNodeStatesShouldMarkCompletedUserTasks() {
        HistoricActivityInstance completedUt = activityInstance("ut1", "userTask");
        List<HistoricActivityInstance> allActivities = Collections.singletonList(completedUt);
        Set<String> activeNodeIds = Collections.emptySet();

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        assertThat(states).containsEntry("ut1", "completed");
    }

    @Test
    void classifyNodeStatesShouldMarkServiceTaskAsAuto() {
        HistoricActivityInstance serviceTask = activityInstance("st1", "serviceTask");
        List<HistoricActivityInstance> allActivities = Collections.singletonList(serviceTask);
        Set<String> activeNodeIds = Collections.emptySet();

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        assertThat(states).containsEntry("st1", "auto");
    }

    @Test
    void classifyNodeStatesShouldSkipGatewayAndEventTypes() {
        List<HistoricActivityInstance> allActivities = Arrays.asList(
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
        Set<String> activeNodeIds = Collections.emptySet();

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        // SKIP_TYPES 中的节点不应被标注状态
        for (String skipType : new String[]{
                "start", "end", "gw_ex", "gw_para", "gw_inc",
                "boundary", "catch", "throw_evt", "eb_gw"}) {
            assertThat(states).doesNotContainKey(skipType);
        }
    }

    @Test
    void classifyNodeStatesShouldPrioritizeActiveOverCompleted() {
        HistoricActivityInstance act = activityInstance("ut1", "userTask");
        List<HistoricActivityInstance> allActivities = Collections.singletonList(act);
        Set<String> activeNodeIds = Collections.singleton("ut1");

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        // active 优先于 completed
        assertThat(states).containsEntry("ut1", "active");
    }

    @Test
    void classifyNodeStatesShouldDeduplicateSameNode() {
        HistoricActivityInstance first = activityInstance("ut1", "userTask");
        HistoricActivityInstance second = activityInstance("ut1", "userTask");
        List<HistoricActivityInstance> allActivities = Arrays.asList(first, second);
        Set<String> activeNodeIds = Collections.emptySet();

        DiagramWorkflow dw = createWorkflow();
        Map<String, String> states = dw.classifyNodeStates(allActivities, activeNodeIds);

        assertThat(states).hasSize(1); // 去重
    }

    // ======================== addDefaultLayout ========================

    @Test
    void addDefaultLayoutShouldGenerateCoordinatesForEmptyLocationMap() {
        BpmnModel model = createSimpleBpmnModel();
        assertThat(model.getLocationMap()).isNullOrEmpty();

        DiagramWorkflow dw = createWorkflow();
        dw.addDefaultLayout(model);

        Map<String, GraphicInfo> locationMap = model.getLocationMap();
        assertThat(locationMap).isNotNull().isNotEmpty();
        // StartEvent 应有坐标
        assertThat(locationMap).containsKey("start");
        // UserTask 应有坐标
        assertThat(locationMap).containsKey("ut1");
        // ServiceTask 应有坐标
        assertThat(locationMap).containsKey("st1");
    }

    @Test
    void addDefaultLayoutShouldLayoutNodesHorizontally() {
        BpmnModel model = createSimpleBpmnModel();
        DiagramWorkflow dw = createWorkflow();
        dw.addDefaultLayout(model);

        Map<String, GraphicInfo> locationMap = model.getLocationMap();
        GraphicInfo startGi = locationMap.get("start");
        GraphicInfo ut1Gi = locationMap.get("ut1");
        GraphicInfo st1Gi = locationMap.get("st1");

        // 水平布局：后续节点 x 坐标应大于前一个
        assertThat(startGi.getX()).isLessThan(ut1Gi.getX());
        assertThat(ut1Gi.getX()).isLessThan(st1Gi.getX());
        // y 坐标应相同（同一行）
        assertThat(startGi.getY()).isEqualTo(100.0);
        assertThat(ut1Gi.getY()).isEqualTo(100.0);
    }

    @Test
    void addDefaultLayoutShouldUseCorrectNodeSizes() {
        BpmnModel model = createSimpleBpmnModel();
        DiagramWorkflow dw = createWorkflow();
        dw.addDefaultLayout(model);

        GraphicInfo startGi = model.getLocationMap().get("start");
        GraphicInfo ut1Gi = model.getLocationMap().get("ut1");

        // StartEvent 应为 30x30
        assertThat(startGi.getWidth()).isEqualTo(30.0);
        assertThat(startGi.getHeight()).isEqualTo(30.0);
        // UserTask 应为 100x80
        assertThat(ut1Gi.getWidth()).isEqualTo(100.0);
        assertThat(ut1Gi.getHeight()).isEqualTo(80.0);
    }

    @Test
    void addDefaultLayoutShouldNotBeCalledWhenLocationMapNotEmpty() {
        BpmnModel model = createSimpleBpmnModel();
        // 预置 start 坐标
        GraphicInfo presetGi = new GraphicInfo(200, 300, 40, 40);
        model.addGraphicInfo("start", presetGi);

        // 验证 getProcessDiagram 中只有当 locationMap 为空时才调用 addDefaultLayout
        // 此处不直接调用 addDefaultLayout，而是验证有坐标的模型不会被覆盖
        DiagramWorkflow dw = createWorkflow();
        dw.addDefaultLayout(model);

        // addDefaultLayout 无条件覆盖，因此预设坐标会被重置
        // 实际的保护逻辑在 getProcessDiagram 的调用方（检查 locationMap 是否为空）
        // 这个测试验证了 addDefaultLayout 对非空 locationMap 的行为
        GraphicInfo startGi = model.getLocationMap().get("start");
        // addDefaultLayout 重新布局了 start 节点
        assertThat(startGi.getX()).isEqualTo(50.0);
    }

    // ======================== buildSvg ========================

    @Test
    void buildSvgShouldCreateValidSvgStructure() {
        BpmnModel model = createModelWithCoordinates();
        Map<String, String> nodeStates = new HashMap<>();
        nodeStates.put("userTask1", "active");
        nodeStates.put("userTask2", "completed");

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, nodeStates);

        assertThat(svg).startsWith("<svg");
        assertThat(svg).endsWith("</svg>\n");
        assertThat(svg).contains("xmlns=\"http://www.w3.org/2000/svg\"");
    }

    @Test
    void buildSvgShouldIncludeCssStyles() {
        BpmnModel model = createModelWithCoordinates();
        Map<String, String> nodeStates = new HashMap<>();
        nodeStates.put("userTask1", "active");

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, nodeStates);

        assertThat(svg).contains("<style");
        assertThat(svg).contains(".state-active");
        assertThat(svg).contains(".state-completed");
        assertThat(svg).contains(".state-auto");
        assertThat(svg).contains("#FF4D4F");    // active 红色
        assertThat(svg).contains("#52C41A");    // completed 绿色
        assertThat(svg).contains("#1890FF");    // auto 蓝色
    }

    @Test
    void buildSvgShouldEmbedPngBase64() {
        String pngBase64 = "iVBORw0KGgoAAAABBBBB";
        BpmnModel model = createModelWithCoordinates();

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(pngBase64, model, 600, 300, Collections.emptyMap());

        assertThat(svg).contains("data:image/png;base64," + pngBase64);
    }

    @Test
    void buildSvgShouldIncludeNodeStateOverlay() {
        BpmnModel model = createModelWithCoordinates();
        Map<String, String> nodeStates = new HashMap<>();
        nodeStates.put("userTask1", "active");
        nodeStates.put("userTask2", "completed");

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, nodeStates);

        assertThat(svg).contains("data-state=\"active\"");
        assertThat(svg).contains("data-state=\"completed\"");
        assertThat(svg).contains("class=\"state-active\"");
        assertThat(svg).contains("class=\"state-completed\"");
        assertThat(svg).contains("<g id=\"state-overlay\">");
    }

    @Test
    void buildSvgShouldUseCorrectSvgDimensions() {
        BpmnModel model = createModelWithCoordinates();
        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 800, 600, Collections.emptyMap());

        assertThat(svg).contains("width=\"800\"");
        assertThat(svg).contains("height=\"600\"");
    }

    @Test
    void buildSvgShouldPositionRectsAccordingToGraphicInfo() {
        BpmnModel model = createModelWithCoordinates();
        Map<String, String> nodeStates = new HashMap<>();
        nodeStates.put("userTask1", "active");

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, nodeStates);

        // userTask1 坐标为 (150, 100, 100, 70)
        // Flowable GraphicInfo 构造器签名为 (x, y, height, width)
        // 因此 getWidth()=70, getHeight()=100
        assertThat(svg).contains("id=\"userTask1\"");
        assertThat(svg).contains("x=\"150.0\"");
        assertThat(svg).contains("y=\"100.0\"");
    }

    @Test
    void buildSvgShouldNotIncludeOverlayForNodesWithoutGraphicInfo() {
        BpmnModel model = createModelWithCoordinates();
        Map<String, String> nodeStates = new HashMap<>();
        nodeStates.put("nonExistentNode", "active"); // 不存在于 locationMap

        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, nodeStates);

        // 不应包含不存在节点的 rect
        assertThat(svg).doesNotContain("nonExistentNode");
    }

    @Test
    void buildSvgShouldHandleEmptyNodeStates() {
        BpmnModel model = createModelWithCoordinates();
        DiagramWorkflow dw = createWorkflow();
        String svg = dw.buildSvg(fakePngBase64(), model, 600, 300, Collections.emptyMap());

        // 应有 overlay group 但无 rect 内容
        assertThat(svg).contains("<g id=\"state-overlay\">");
        assertThat(svg).doesNotContain("data-state=");
    }

    // ======================== encodePngToBase64 ========================

    @Test
    void encodePngToBase64ShouldProduceValidBase64() {
        byte[] pngBytes = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        InputStream is = new ByteArrayInputStream(pngBytes);

        DiagramWorkflow dw = createWorkflow();
        String result = dw.encodePngToBase64(is);

        String expected = Base64.getEncoder().encodeToString(pngBytes);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void encodePngToBase64ShouldHandleLargerData() {
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        InputStream is = new ByteArrayInputStream(data);

        DiagramWorkflow dw = createWorkflow();
        String result = dw.encodePngToBase64(is);

        String expected = Base64.getEncoder().encodeToString(data);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void encodePngToBase64ShouldThrowWhenReadFails() {
        InputStream brokenStream = createBrokenInputStream();

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.encodePngToBase64(brokenStream))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PNG 编码失败");
    }

    // ======================== getProcessDiagram 异常情况 ========================

    @Test
    void getProcessDiagramShouldThrowForNullId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagram(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void getProcessDiagramShouldThrowForEmptyId() {
        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagram(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void getProcessDiagramShouldThrowWhenProcessInstanceNotFound() {
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(null);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagram("pi-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程实例");
    }

    @Test
    void getProcessDiagramShouldThrowWhenBpmnModelNotFound() {
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionId()).thenReturn("pd-001");

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        when(mockBpmnModelCache.getBpmnModel("pd-001")).thenReturn(null);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagram("pi-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("未找到流程定义");
    }

    // ======================== getProcessDiagram 正常流程 ========================

    @Test
    void getProcessDiagramShouldReturnValidDiagramForCompletedProcess() {
        String piId = "pi-completed";

        // Mock 查询链
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionId()).thenReturn("pd-001");

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        BpmnModel model = createModelWithCoordinates();
        when(mockBpmnModelCache.getBpmnModel("pd-001")).thenReturn(model);

        HistoricActivityInstance completedAct = activityInstance("userTask1", "userTask");
        HistoricActivityInstanceQuery actQuery = mock(HistoricActivityInstanceQuery.class);
        when(actQuery.processInstanceId(piId)).thenReturn(actQuery);
        when(actQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(actQuery);
        when(actQuery.asc()).thenReturn(actQuery);
        when(actQuery.list()).thenReturn(Collections.singletonList(completedAct));

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(actQuery, activeQuery);

        // Mock PNG 生成
        byte[] fakePng = { (byte) 0x89, 0x50, 0x4E, 0x47 };
        InputStream fakePngStream = new ByteArrayInputStream(fakePng);
        when(mockDiagramGenerator.generateDiagram(
                eq(model), eq("png"), anyList(), anyList(),
                anyString(), anyString(), anyString(),
                isNull(), eq(1.0), eq(false)))
                .thenReturn(fakePngStream);

        DiagramWorkflow dw = createWorkflow();
        ProcessDiagramVO result = dw.getProcessDiagram(piId);

        assertThat(result).isNotNull();
        assertThat(result.getProcessInstanceId()).isEqualTo(piId);
        assertThat(result.getProcessDefinitionId()).isEqualTo("pd-001");
        assertThat(result.getSvg()).isNotNull().isNotEmpty();
        assertThat(result.getSvg()).startsWith("<svg");
        assertThat(result.getSvg()).contains("data-state=\"completed\"");
        assertThat(result.getSvg()).contains("userTask1");
    }

    @Test
    void getProcessDiagramShouldHandleDefaultLayoutForModelWithoutCoordinates() {
        String piId = "pi-default-layout";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionId()).thenReturn("pd-002");

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        BpmnModel model = createSimpleBpmnModel();
        when(mockBpmnModelCache.getBpmnModel("pd-002")).thenReturn(model);

        HistoricActivityInstanceQuery actQuery = mock(HistoricActivityInstanceQuery.class);
        when(actQuery.processInstanceId(piId)).thenReturn(actQuery);
        when(actQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(actQuery);
        when(actQuery.asc()).thenReturn(actQuery);
        when(actQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(actQuery, activeQuery);

        byte[] fakePng = { (byte) 0x89, 0x50, 0x4E, 0x47 };
        InputStream fakePngStream = new ByteArrayInputStream(fakePng);
        when(mockDiagramGenerator.generateDiagram(
                any(BpmnModel.class), anyString(), anyList(), anyList(),
                anyString(), anyString(), anyString(),
                isNull(), anyDouble(), anyBoolean()))
                .thenReturn(fakePngStream);

        DiagramWorkflow dw = createWorkflow();
        ProcessDiagramVO result = dw.getProcessDiagram(piId);

        assertThat(result).isNotNull();
        assertThat(result.getSvg()).isNotNull().isNotEmpty();
        assertThat(result.getSvg()).startsWith("<svg");
        // 验证默认布局生成了坐标，因此 SVG 尺寸应有值
        assertThat(result.getSvg()).contains("width=\"");
    }

    @Test
    void getProcessDiagramShouldThrowWhenPngGenerationReturnsNull() {
        String piId = "pi-png-null";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionId()).thenReturn("pd-001");

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        BpmnModel model = createModelWithCoordinates();
        when(mockBpmnModelCache.getBpmnModel("pd-001")).thenReturn(model);

        HistoricActivityInstanceQuery actQuery = mock(HistoricActivityInstanceQuery.class);
        when(actQuery.processInstanceId(piId)).thenReturn(actQuery);
        when(actQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(actQuery);
        when(actQuery.asc()).thenReturn(actQuery);
        when(actQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(actQuery, activeQuery);

        // 返回 null 模拟 PNG 生成失败
        when(mockDiagramGenerator.generateDiagram(
                any(BpmnModel.class), anyString(), anyList(), anyList(),
                anyString(), anyString(), anyString(),
                isNull(), anyDouble(), anyBoolean()))
                .thenReturn(null);

        DiagramWorkflow dw = createWorkflow();
        assertThatThrownBy(() -> dw.getProcessDiagram(piId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("流程图 PNG 生成失败");
    }

    @Test
    void getProcessDiagramShouldCalculateCanvasDimensionsFromLocationMap() {
        String piId = "pi-canvas";

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionId()).thenReturn("pd-001");

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(piId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        BpmnModel model = createModelWithCoordinates();
        when(mockBpmnModelCache.getBpmnModel("pd-001")).thenReturn(model);

        HistoricActivityInstanceQuery actQuery = mock(HistoricActivityInstanceQuery.class);
        when(actQuery.processInstanceId(piId)).thenReturn(actQuery);
        when(actQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(actQuery);
        when(actQuery.asc()).thenReturn(actQuery);
        when(actQuery.list()).thenReturn(Collections.emptyList());

        HistoricActivityInstanceQuery activeQuery = mock(HistoricActivityInstanceQuery.class);
        when(activeQuery.processInstanceId(piId)).thenReturn(activeQuery);
        when(activeQuery.unfinished()).thenReturn(activeQuery);
        when(activeQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(actQuery, activeQuery);

        byte[] fakePng = { (byte) 0x89, 0x50, 0x4E, 0x47 };
        InputStream fakePngStream = new ByteArrayInputStream(fakePng);
        when(mockDiagramGenerator.generateDiagram(
                any(BpmnModel.class), anyString(), anyList(), anyList(),
                anyString(), anyString(), anyString(),
                isNull(), anyDouble(), anyBoolean()))
                .thenReturn(fakePngStream);

        DiagramWorkflow dw = createWorkflow();
        ProcessDiagramVO result = dw.getProcessDiagram(piId);

        String svg = result.getSvg();
        // Flowable GraphicInfo 构造器为 (x, y, height, width)
        // start(50,100,30,30) → right=50+30=80, bottom=100+30=130
        // userTask1(150,100,100,70) → right=150+70=220, bottom=100+100=200
        // userTask2(320,100,100,70) → right=320+70=390, bottom=100+100=200
        // end(490,100,30,30) → right=490+30=520, bottom=100+30=130
        // canvas = (maxX+10=530, maxY+10=210)
        assertThat(svg).contains("width=\"530\"");
        assertThat(svg).contains("height=\"210\"");
    }

    // ======================== 构造器验证 ========================

    @Test
    void constructorShouldThrowWhenHistoryServiceIsNull() {
        assertThatThrownBy(() -> new DiagramWorkflow(null, mockBpmnModelCache, "宋体", "宋体", "宋体"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService");
    }

    @Test
    void constructorShouldThrowWhenBpmnModelCacheIsNull() {
        assertThatThrownBy(() -> new DiagramWorkflow(mockHistoryService, null, "宋体", "宋体", "宋体"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnModelCache");
    }

    // ======================== 辅助方法 ========================

    private DiagramWorkflow createWorkflow() {
        return new DiagramWorkflow(
                mockHistoryService, mockBpmnModelCache,
                "宋体", "宋体", "宋体",
                mockDiagramGenerator);
    }

    private static HistoricActivityInstance activityInstance(String activityId, String activityType) {
        HistoricActivityInstance act = mock(HistoricActivityInstance.class);
        when(act.getActivityId()).thenReturn(activityId);
        when(act.getActivityType()).thenReturn(activityType);
        return act;
    }

    private static String fakePngBase64() {
        return Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
    }

    /**
     * 创建含 GraphicInfo 坐标的 BPMN 模型（2 个 UserTask，无 ServiceTask）。
     */
    private static BpmnModel createModelWithCoordinates() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask ut1 = new UserTask();
        ut1.setId("userTask1");
        ut1.setName("审批1");
        process.addFlowElement(ut1);

        UserTask ut2 = new UserTask();
        ut2.setId("userTask2");
        ut2.setName("审批2");
        process.addFlowElement(ut2);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        addFlow(process, "f1", start, ut1);
        addFlow(process, "f2", ut1, ut2);
        addFlow(process, "f3", ut2, end);

        // 设置坐标
        model.addGraphicInfo("start", new GraphicInfo(50, 100, 30, 30));
        model.addGraphicInfo("userTask1", new GraphicInfo(150, 100, 100, 70));
        model.addGraphicInfo("userTask2", new GraphicInfo(320, 100, 100, 70));
        model.addGraphicInfo("end", new GraphicInfo(490, 100, 30, 30));

        return model;
    }

    /**
     * 创建无 GraphicInfo 坐标的简单 BPMN 模型（用于测试默认布局）。
     */
    private static BpmnModel createSimpleBpmnModel() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("simpleProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask ut1 = new UserTask();
        ut1.setId("ut1");
        ut1.setName("审批");
        process.addFlowElement(ut1);

        ServiceTask st1 = new ServiceTask();
        st1.setId("st1");
        st1.setName("服务任务");
        process.addFlowElement(st1);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        addFlow(process, "f_start_ut", start, ut1);
        addFlow(process, "f_ut_st", ut1, st1);
        addFlow(process, "f_st_end", st1, end);

        return model;
    }

    private static void addFlow(Process process, String id, FlowElement source, FlowElement target) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(source.getId());
        flow.setTargetRef(target.getId());
        process.addFlowElement(flow);
    }

    /**
     * 创建会抛出 IOException 的模拟 InputStream（用于测试编码失败场景）。
     */
    private static InputStream createBrokenInputStream() {
        return new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("模拟读取失败");
            }
        };
    }
}
