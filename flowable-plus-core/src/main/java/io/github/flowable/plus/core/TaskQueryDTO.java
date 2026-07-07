package io.github.flowable.plus.core;

import lombok.Data;

import java.util.Date;

/**
 * 待办/已办列表查询条件。
 *
 * <p>userId 不放在 DTO 中，由 API 方法参数显式传入，语义更清晰。</p>
 *
 * @author flowable-plus
 */
@Data
public class TaskQueryDTO {

    /** 页码（从 1 开始，默认 1） */
    private int pageNum = 1;

    /** 每页大小（默认 20） */
    private int pageSize = 20;

    /** 流程定义 Key（精确匹配） */
    private String processDefinitionKey;

    /** 节点名称（精确匹配） */
    private String taskName;

    /** 模糊搜索（businessKey + 流程定义名称） */
    private String keyword;

    /** 创建时间起 */
    private Date beginDate;

    /** 创建时间止 */
    private Date endDate;
}
