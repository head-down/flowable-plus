package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.image.impl.DefaultProcessDiagramGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程图生成模块，一次全量查询 + 内存分类，生成带节点状态标注的 SVG 流程图。
 *
 * <p>节点状态分类：
 * <ul>
 *   <li>active — 当前活跃任务节点</li>
 *   <li>completed — 已完成审批节点（UserTask）</li>
 *   <li>auto — 已完成的自动节点（ServiceTask 等）</li>
 *   <li>flow-passed — 已通过的连线</li>
 * </ul>
 *
 * @author flowable-plus
 */
public class DiagramWorkflow {

    private static final Logger log = LoggerFactory.getLogger(DiagramWorkflow.class);

    /** Gateways 和事件的 BPMN 类型后缀，不需要标注状态 */
    private static final Set<String> SKIP_TYPES = new HashSet<>();

    static {
        SKIP_TYPES.add("startEvent");
        SKIP_TYPES.add("endEvent");
        SKIP_TYPES.add("exclusiveGateway");
        SKIP_TYPES.add("parallelGateway");
        SKIP_TYPES.add("inclusiveGateway");
        SKIP_TYPES.add("eventBasedGateway");
        SKIP_TYPES.add("boundaryEvent");
        SKIP_TYPES.add("intermediateCatchEvent");
        SKIP_TYPES.add("intermediateThrowEvent");
    }

    private final HistoryService historyService;
    private final BpmnModelCache bpmnModelCache;
    private final ProcessDiagramGenerator diagramGenerator;

    public DiagramWorkflow(HistoryService historyService, BpmnModelCache bpmnModelCache) {
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        this.historyService = historyService;
        this.bpmnModelCache = bpmnModelCache;
        this.diagramGenerator = new DefaultProcessDiagramGenerator();
    }

    /**
     * 获取流程实例的流程图 SVG，包含节点状态标注。
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 含节点状态标注的流程图 VO
     * @throws NotFoundException 如果流程实例不存在
     */
    public ProcessDiagramVO getProcessDiagram(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 查询流程实例
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (hpi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }
        String processDefinitionId = hpi.getProcessDefinitionId();

        // 2. 获取 BPMN 模型
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("未找到流程定义 " + processDefinitionId);
        }

        // 如果模型缺少 GraphicInfo 坐标，添加默认布局兜底
        if (bpmnModel.getLocationMap() == null || bpmnModel.getLocationMap().isEmpty()) {
            addDefaultLayout(bpmnModel);
        }

        // 3. 查询所有 HistoricActivityInstance（按时间升序）
        List<HistoricActivityInstance> allActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();

        // 4. 查询未完成的活动实例（当前活跃节点）
        List<HistoricActivityInstance> activeActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .unfinished()
                .list();
        Set<String> activeNodeIds = activeActivities.stream()
                .map(HistoricActivityInstance::getActivityId)
                .collect(Collectors.toSet());

        // 5. 分类节点状态
        Map<String, String> nodeStates = classifyNodeStates(allActivities, activeNodeIds);
        Set<String> executedNodeIds = allActivities.stream()
                .map(HistoricActivityInstance::getActivityId)
                .collect(Collectors.toCollection(HashSet::new));

        // 6. 分类连线状态
        Set<String> flowPassedIds = classifyFlowStates(bpmnModel, executedNodeIds);

        log.debug("getProcessDiagram: processInstanceId={}, nodeStates={}, flowPassed={}",
                processInstanceId, nodeStates.size(), flowPassedIds.size());

        // 7. 生成 PNG 底图并封装为 SVG
        // Flowable 6.8.0 的 ProcessDiagramGenerator 仅支持光栅输出(PNG/JPG)
        // 生成 PNG 后以 base64 嵌入 SVG，再叠加 data-state 标注层和 CSS
        InputStream pngStream = diagramGenerator.generatePngDiagram(bpmnModel, 1.0, false);
        if (pngStream == null) {
            throw new RuntimeException("流程图 PNG 生成失败");
        }
        String pngBase64 = encodePngToBase64(pngStream);
        int canvasWidth = calcCanvasWidth(bpmnModel);
        int canvasHeight = calcCanvasHeight(bpmnModel);
        String svg = buildSvg(pngBase64, bpmnModel, canvasWidth, canvasHeight, nodeStates, flowPassedIds);

        return ProcessDiagramVO.builder()
                .processInstanceId(processInstanceId)
                .processDefinitionId(processDefinitionId)
                .svg(svg)
                .build();
    }

    // ======================== 状态分类 ========================

    /**
     * 分类节点状态：
     * - active: 有未完成的历史活动实例
     * - completed: UserTask 类型且已完成
     * - auto: 非 UserTask、非网关/事件的类型且已完成
     */
    private Map<String, String> classifyNodeStates(
            List<HistoricActivityInstance> allActivities,
            Set<String> activeNodeIds) {

        Map<String, String> states = new HashMap<>();
        Set<String> processed = new HashSet<>();

        for (HistoricActivityInstance act : allActivities) {
            String nodeId = act.getActivityId();
            if (processed.contains(nodeId)) {
                continue;
            }
            processed.add(nodeId);

            // active 优先
            if (activeNodeIds.contains(nodeId)) {
                states.put(nodeId, "active");
                continue;
            }

            // 已完成的，按类型分类
            String type = act.getActivityType();
            if (SKIP_TYPES.contains(type)) {
                continue;
            }

            if ("userTask".equals(type)) {
                states.put(nodeId, "completed");
            } else {
                states.put(nodeId, "auto");
            }
        }

        return states;
    }

    /**
     * 分类连线状态：若 SequenceFlow 的 source 和 target 都已被执行，则标记为 flow-passed。
     */
    private Set<String> classifyFlowStates(BpmnModel bpmnModel, Set<String> executedNodeIds) {
        Set<String> flowPassed = new HashSet<>();
        for (Process process : bpmnModel.getProcesses()) {
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof SequenceFlow) {
                    SequenceFlow flow = (SequenceFlow) element;
                    if (executedNodeIds.contains(flow.getSourceRef())
                            && executedNodeIds.contains(flow.getTargetRef())) {
                        flowPassed.add(flow.getId());
                    }
                }
            }
        }
        return flowPassed;
    }

    // ======================== SVG 构建 ========================

    private String encodePngToBase64(InputStream is) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, n);
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("PNG 编码失败", e);
        }
    }

    private int calcCanvasWidth(BpmnModel bpmnModel) {
        double maxX = 0;
        for (GraphicInfo gi : bpmnModel.getLocationMap().values()) {
            double right = gi.getX() + gi.getWidth();
            if (right > maxX) {
                maxX = right;
            }
        }
        return (int) maxX + 30;
    }

    private int calcCanvasHeight(BpmnModel bpmnModel) {
        double maxY = 0;
        for (GraphicInfo gi : bpmnModel.getLocationMap().values()) {
            double bottom = gi.getY() + gi.getHeight();
            if (bottom > maxY) {
                maxY = bottom;
            }
        }
        return (int) maxY + 30;
    }

    /**
     * 构建含 PNG 底图和 data-state 标注层的完整 SVG。
     */
    private String buildSvg(String pngBase64, BpmnModel bpmnModel, int width, int height,
                             Map<String, String> nodeStates, Set<String> flowPassedIds) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
                .append("width=\"").append(width).append("\" ")
                .append("height=\"").append(height).append("\">\n");

        // CSS 样式
        svg.append("<defs>\n")
                .append("  <style type=\"text/css\">\n")
                .append("    .state-active { fill: #FF4D4F !important; fill-opacity: 0.3; ")
                .append("stroke: #FF4D4F !important; stroke-width: 2px; }\n")
                .append("    .state-completed { fill: #52C41A !important; fill-opacity: 0.3; ")
                .append("stroke: #52C41A !important; stroke-width: 2px; }\n")
                .append("    .state-auto { fill: #1890FF !important; fill-opacity: 0.3; ")
                .append("stroke: #1890FF !important; stroke-width: 2px; }\n")
                .append("  </style>\n")
                .append("</defs>\n");

        // PNG 底图
        svg.append("<image x=\"0\" y=\"0\" width=\"").append(width).append("\" ")
                .append("height=\"").append(height).append("\" ")
                .append("href=\"data:image/png;base64,").append(pngBase64).append("\"/>\n");

        // data-state 标注层
        svg.append("<g id=\"state-overlay\">\n");
        Map<String, GraphicInfo> locationMap = bpmnModel.getLocationMap();
        for (Map.Entry<String, String> entry : nodeStates.entrySet()) {
            String nodeId = entry.getKey();
            String state = entry.getValue();
            GraphicInfo gi = locationMap.get(nodeId);
            if (gi != null) {
                svg.append("  <rect id=\"").append(nodeId).append("\" ")
                        .append("data-state=\"").append(state).append("\" ")
                        .append("class=\"state-").append(state).append("\" ")
                        .append("x=\"").append(gi.getX()).append("\" ")
                        .append("y=\"").append(gi.getY()).append("\" ")
                        .append("width=\"").append(gi.getWidth()).append("\" ")
                        .append("height=\"").append(gi.getHeight()).append("\" ")
                        .append("rx=\"5\" ry=\"5\" fill-opacity=\"0.3\"/>\n");
            }
        }
        svg.append("</g>\n");

        svg.append("</svg>\n");
        return svg.toString();
    }

    // ======================== 默认布局 ========================

    /**
     * 为不包含 GraphicInfo 坐标的 BPMN 模型添加默认水平布局。
     */
    private void addDefaultLayout(BpmnModel bpmnModel) {
        double x = 50;
        double y = 100;
        double taskWidth = 100;
        double taskHeight = 80;
        double eventSize = 30;
        double xGap = 60;

        for (Process process : bpmnModel.getProcesses()) {
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof StartEvent) {
                    bpmnModel.addGraphicInfo(element.getId(),
                            new GraphicInfo(x, y, eventSize, eventSize));
                    x += eventSize + xGap;
                }
            }
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof UserTask) {
                    bpmnModel.addGraphicInfo(element.getId(),
                            new GraphicInfo(x, y, taskWidth, taskHeight));
                    x += taskWidth + xGap;
                }
            }
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof ServiceTask) {
                    bpmnModel.addGraphicInfo(element.getId(),
                            new GraphicInfo(x, y, taskWidth, taskHeight));
                    x += taskWidth + xGap;
                }
            }
        }
    }
}
