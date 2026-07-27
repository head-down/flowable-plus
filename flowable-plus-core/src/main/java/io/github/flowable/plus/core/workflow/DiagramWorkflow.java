package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.Process;
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
import java.util.ArrayList;
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

    /** 中文字体自动检测降级链 */
    private static final String[] CJK_FALLBACK_FONTS = {
            "宋体", "SimSun",
            "微软雅黑", "Microsoft YaHei",
            "文泉驿微米黑", "WenQuanYi Micro Hei",
            "Noto Sans CJK SC", "Noto Sans SC",
            "Source Han Sans SC",
            "SimHei"
    };

    private final HistoryService historyService;
    private final BpmnModelCache bpmnModelCache;
    private final String activityFont;
    private final String labelFont;
    private final String annotationFont;
    private final ProcessDiagramGenerator diagramGenerator;

    /**
     * 创建 DiagramWorkflow，指定流程图渲染字体。
     * <p>字体通过 {@link ProcessDiagramGenerator#generateDiagram} 方法参数传入，
     * 而非 {@link DefaultProcessDiagramGenerator} 构造器（该构造器不接受字体参数）。</p>
     *
     * @param historyService  Flowable 历史服务
     * @param bpmnModelCache  BPMN 模型缓存
     * @param activityFont    活动节点字体名，如 "宋体"、"微软雅黑" 等
     * @param labelFont       标签/连线字体名
     * @param annotationFont  注解字体名
     */
    public DiagramWorkflow(HistoryService historyService, BpmnModelCache bpmnModelCache,
                           String activityFont, String labelFont, String annotationFont) {
        this(historyService, bpmnModelCache, activityFont, labelFont, annotationFont, null);
    }

    /**
     * 包级可见构造器，允许注入自定义 {@link ProcessDiagramGenerator} 用于测试。
     * 若 diagramGenerator 为 null，则使用默认 {@link DefaultProcessDiagramGenerator}。
     */
    DiagramWorkflow(HistoryService historyService, BpmnModelCache bpmnModelCache,
                    String activityFont, String labelFont, String annotationFont,
                    ProcessDiagramGenerator diagramGenerator) {
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        this.historyService = historyService;
        this.bpmnModelCache = bpmnModelCache;
        this.activityFont = resolveFont(activityFont);
        this.labelFont = resolveFont(labelFont);
        this.annotationFont = resolveFont(annotationFont);
        this.diagramGenerator = diagramGenerator != null
                ? diagramGenerator
                : new DefaultProcessDiagramGenerator();
    }

    /**
     * @deprecated 使用 {@link #DiagramWorkflow(HistoryService, BpmnModelCache, String, String, String)}
     *             指定中文字体，避免流程图节点名显示为方块。
     */
    @Deprecated
    public DiagramWorkflow(HistoryService historyService, BpmnModelCache bpmnModelCache) {
        this(historyService, bpmnModelCache, "宋体", "宋体", "宋体");
    }

    // ======================== 字体解析 ========================

    /**
     * 解析字体名称：若系统不支持指定字体，按降级链尝试其他中文字体。
     *
     * <p>在 Linux/Docker 等可能缺少中文字体的环境中，此方法尝试找到可用的中文字体。
     * 如果所有中文字体都不可用，则返回原名称并记录警告（Java 将使用默认字体，
     * 可能导致中文显示为方块）。</p>
     *
     * @param desiredFont 期望的字体名称
     * @return 系统可用的字体名称
     */
    static String resolveFont(String desiredFont) {
        if (desiredFont == null || desiredFont.isEmpty()) {
            return "宋体";
        }

        try {
            java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            String[] available = ge.getAvailableFontFamilyNames();

            // 精确匹配
            for (String font : available) {
                if (font.equals(desiredFont)) {
                    return desiredFont;
                }
            }

            // 忽略大小写匹配
            for (String font : available) {
                if (font.equalsIgnoreCase(desiredFont)) {
                    return font; // 使用系统中的规范名称
                }
            }

            // 降级链：尝试常见中文字体
            for (String fallback : CJK_FALLBACK_FONTS) {
                for (String font : available) {
                    if (font.equalsIgnoreCase(fallback)) {
                        log.warn("期望字体 '{}' 在系统中未找到，降级使用 '{}'",
                                desiredFont, font);
                        return font;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("无法枚举系统字体: {}。直接使用指定字体 '{}'。",
                    e.getMessage(), desiredFont);
            return desiredFont;
        }

        log.warn("系统中未找到任何中文字体。期望字体 '{}' 将被使用，"
                + "但中文可能显示为方块。请安装中文字体（如 'Noto Sans CJK SC'）。", desiredFont);
        return desiredFont;
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

        log.debug("getProcessDiagram: processInstanceId={}, nodeStates={}",
                processInstanceId, nodeStates.size());

        // 6. 生成 PNG 底图并封装为 SVG
        // 使用带字体参数的 generateDiagram 而非 generatePngDiagram，
        // 确保中文字体正确渲染，避免显示为方块（□□□）。
        InputStream pngStream = diagramGenerator.generateDiagram(
                bpmnModel, "png",
                new ArrayList<String>(), new ArrayList<String>(),
                activityFont, labelFont, annotationFont,
                null, 1.0, false);
        if (pngStream == null) {
            throw new RuntimeException("流程图 PNG 生成失败");
        }
        String pngBase64 = encodePngToBase64(pngStream);

        // 单次遍历 locationMap 计算画布尺寸。
        // PNG 与 SVG 均按 BPMN 绝对坐标定位，画布尺寸与 DefaultProcessDiagramGenerator
        // 保持一致（maxX+10 × maxY+10），以避免缩放错位。
        double maxX = 0, maxY = 0;
        for (GraphicInfo gi : bpmnModel.getLocationMap().values()) {
            double right = gi.getX() + gi.getWidth();
            double bottom = gi.getY() + gi.getHeight();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }
        int canvasWidth = (int) maxX + 10;
        int canvasHeight = (int) maxY + 10;

        String svg = buildSvg(pngBase64, bpmnModel, canvasWidth, canvasHeight,
                nodeStates);

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
    Map<String, String> classifyNodeStates(
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

    // ======================== SVG 构建 ========================

    String encodePngToBase64(InputStream is) {
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

    /**
     * 构建含 PNG 底图和 data-state 标注层的完整 SVG。
     */
    String buildSvg(String pngBase64, BpmnModel bpmnModel, int width, int height,
                             Map<String, String> nodeStates) {
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

        // data-state 标注层 —— 按 BPMN 绝对坐标定位，与 PNG 底层保持一致
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
    void addDefaultLayout(BpmnModel bpmnModel) {
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
                            new GraphicInfo(x, y, taskHeight, taskWidth));
                    x += taskWidth + xGap;
                }
            }
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof ServiceTask) {
                    bpmnModel.addGraphicInfo(element.getId(),
                            new GraphicInfo(x, y, taskHeight, taskWidth));
                    x += taskWidth + xGap;
                }
            }
        }
    }
}
