package io.github.flowable.plus.core.strategy;

import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;

import java.util.Date;
import java.util.List;

/**
 * 内置的上一审批节点选择策略。
 *
 * @author flowable-plus
 */
public final class PreviousNodeResolvers {

    private PreviousNodeResolvers() {
    }

    /**
     * 取列表中的第一个候选节点，无额外 DB 查询，性能最优。
     *
     * <p>适用场景：并行网关汇合后，各分支地位等价，任意回退到其中一个即可。</p>
     */
    public static PreviousNodeResolutionStrategy firstCandidate() {
        return (candidates, processInstanceId, historyService) -> candidates.get(0);
    }

    /**
     * 取开始时间最早的候选节点（即"时间最远的那个"）。
     *
     * <p>对每个候选节点查询一次 finished 的 {@link HistoricActivityInstance}，
     * 按 startTime 升序取第一条，比较各候选的 startTime 后返回最早的那一个。</p>
     */
    public static PreviousNodeResolutionStrategy earliestStarted() {
        return (candidates, processInstanceId, historyService) -> {
            String result = null;
            Date earliestTime = null;

            for (String candidate : candidates) {
                List<HistoricActivityInstance> list = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .activityId(candidate)
                        .finished()
                        .orderByHistoricActivityInstanceStartTime().asc()
                        .listPage(0, 1);
                if (!list.isEmpty()) {
                    Date startTime = list.get(0).getStartTime();
                    if (earliestTime == null || (startTime != null && startTime.before(earliestTime))) {
                        result = candidate;
                        earliestTime = startTime;
                    }
                }
            }

            return result != null ? result : candidates.get(0);
        };
    }

    /**
     * 取结束时间最近的候选节点（即"最近的那个"）。
     *
     * <p>对每个候选节点查询一次 finished 的 {@link HistoricActivityInstance}，
     * 按 endTime 降序取第一条，比较各候选的 endTime 后返回最晚的那一个。</p>
     */
    public static PreviousNodeResolutionStrategy latestEnded() {
        return (candidates, processInstanceId, historyService) -> {
            String result = null;
            Date latestTime = null;

            for (String candidate : candidates) {
                List<HistoricActivityInstance> list = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .activityId(candidate)
                        .finished()
                        .orderByHistoricActivityInstanceEndTime().desc()
                        .listPage(0, 1);
                if (!list.isEmpty()) {
                    Date endTime = list.get(0).getEndTime();
                    if (latestTime == null || (endTime != null && endTime.after(latestTime))) {
                        result = candidate;
                        latestTime = endTime;
                    }
                }
            }

            return result != null ? result : candidates.get(0);
        };
    }
}
