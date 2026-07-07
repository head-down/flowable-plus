package io.github.flowable.plus.core;

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

    /** 总记录数 */
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
