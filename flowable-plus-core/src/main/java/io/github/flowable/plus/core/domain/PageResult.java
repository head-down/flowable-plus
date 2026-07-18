package io.github.flowable.plus.core.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 分页结果通用类型。
 *
 * <p>pageNum 从 1 开始，与 MyBatis-Plus 风格一致。
 * 不引入第三方分页依赖，保持 core 模块零外部耦合。</p>
 *
 * @param <T> 分页数据类型
 * @author flowable-plus
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 总记录数。
     *
     * <p><b>注意：</b>在已办任务查询场景下，此值为近似值而非精确值。
     * 这是因为已办查询采用两阶段策略，Phase 1 的 {@code involvedUser}
     * 候选集范围比 Phase 2 的 {@code taskAssignee} 更宽。
     * 建议使用 {@code records.size() < pageSize} 判断是否有下一页，
     * 而非基于此值计算精确总页数。</p>
     */
    private long total;

    /** 当前页码（从 1 开始） */
    private int pageNum;

    /** 每页大小 */
    private int pageSize;

    /** 当前页数据 */
    private List<T> records;

    /**
     * 创建空结果。
     */
    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(0, pageNum, pageSize, Collections.emptyList());
    }
}
