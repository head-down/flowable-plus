package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下一节点审批人 VO（按节点分组）。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeApproverVO {

    /** 节点 definitionKey */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 该节点的审批人列表 */
    private List<ApproverInfoVO> approvers;
}
