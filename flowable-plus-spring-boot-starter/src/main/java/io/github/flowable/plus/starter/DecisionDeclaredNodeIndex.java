package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionNodeDeclaration;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.FlowElementsContainer;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声明面索引（{@code nodeId → UserTask 元素}；starter <b>包内</b>实现细节）。
 *
 * <p>由 starter 构建并预热（{@code #56} 决议 §一「补充登记」的预热形态）：拉面回调被冻结为
 * 「零引擎命令」，节点声明只能从本索引<b>纯读</b>取得；<b>索引未命中 = 未声明，fail-closed</b>
 * （冷启动 / 未预热节点不触发，登记过的已知边界）。索引只收录<b>本机制 URI 下带启用声明</b>
 * （{@code decisionEnabled} 属性在场）的 UserTask —— 与主闸的部署期判定同源；取值的解析
 * （只认小写字面量等口径）<b>不在本类</b>，归 extension 的读取器（监听器回调内完成），
 * 本类不做第二份解析。</p>
 *
 * <p><b>预热时机（预热入口自选的落定形态）</b>：由 {@link DecisionTaskCreatedListenerAdapter} 在
 * <b>首次到点事件</b>时执行预热并构建真身 —— 既有约束逼出：flowable 的 classpath 自动部署发生在
 * {@code SmartLifecycle#start()}（全部单例 Bean 创建完之后），Bean 构造期预热必然得到空索引；
 * 而监听器构造缝对索引做防御性拷贝、此后冻结。首次到点事件时部署必然已完成，一次预热即覆盖
 * 全部已部署定义（含自动部署与显式部署），<b>冷启动节点不丢失</b>。</p>
 *
 * <p><b>nodeId 跨流程定义碰撞</b>（{@code #56} 边界硬要求 ②）：同一 {@code nodeId} 来自不同流程定义
 * 的不同元素时，<b>剔除该键 + 一条 WARN</b>（宁 fail-closed 不误触发）；同一元素重复合入
 * （预热幂等）不是碰撞。</p>
 */
final class DecisionDeclaredNodeIndex {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionDeclaredNodeIndex.class);

    /** 索引本体（{@code nodeId → UserTask 元素}） */
    private final ConcurrentHashMap<String, BaseElement> elements = new ConcurrentHashMap<>();

    /**
     * 合入一批声明节点（预热入口）。
     *
     * @param additions {@code nodeId → UserTask 元素}；不得为 null
     */
    void merge(final Map<String, BaseElement> additions) {
        for (final Map.Entry<String, BaseElement> entry : additions.entrySet()) {
            final String nodeId = entry.getKey();
            final BaseElement element = entry.getValue();
            final BaseElement existing = elements.putIfAbsent(nodeId, element);
            if (existing != null && existing != element) {
                // 跨流程定义碰撞：剔除该键（宁 fail-closed 不误触发），只 WARN 不 fail-fast。
                elements.remove(nodeId, existing);
                LOG.warn("声明面索引检测到 nodeId 跨流程定义碰撞，剔除该键（fail-closed）：nodeId={}", nodeId);
            }
        }
    }

    /**
     * 启动快照（监听器构造缝的入参形态；此后索引不再对监听器可见）。
     *
     * @return 只读快照
     */
    Map<String, BaseElement> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    /**
     * 收集一个容器（流程 / 嵌套子流程）内全部带启用声明的 UserTask。
     *
     * <p>扫描域含嵌套子流程（与主闸的部署期扫描域一致）；「带启用声明」= 本机制 URI 下
     * {@code decisionEnabled} 属性<b>在场</b>（任意取值 —— 取值的合法性解析归监听器回调内的读取器）。</p>
     *
     * <p><b>递归的栈溢出考量</b>：递归深度 = BPMN 嵌套子流程深度，属建模面受控输入（与主闸
     * validator 的 {@code INCLUDE_SUB_PROCESS_CONTENTS} 同构递归）；不设深度护栏与主闸口径一致，
     * 深度异常的模型会在部署期被引擎自身解析与校验路径拦截。</p>
     *
     * @param container 流程或子流程，不得为 null
     * @param into      收集目标，就地追加
     */
    static void collectDeclaredUserTasks(final FlowElementsContainer container, final Map<String, BaseElement> into) {
        container.getFlowElements().forEach(flowElement -> {
            if (flowElement instanceof SubProcess) {
                collectDeclaredUserTasks((SubProcess) flowElement, into);
                return;
            }
            if (flowElement instanceof UserTask
                    && flowElement.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                            DecisionNodeDeclaration.DECISION_ENABLED) != null) {
                into.put(flowElement.getId(), flowElement);
            }
        });
    }
}
