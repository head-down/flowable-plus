package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.dto.TaskQueryDTO;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 已办精确分页查询（ADR-0013）的 Native SQL 构建模块。
 *
 * <p>输入用户 ID 与查询条件，返回可直接传给
 * {@code NativeHistoricProcessInstanceQuery.sql(String)} 的完整 SQL。
 * 参数值在拼接前经 {@link #escapeSql(String)} / {@link #escapeLike(String)}
 * 转义，避免 SQL 注入；日期格式化为 {@code yyyy-MM-dd HH:mm:ss}。</p>
 *
 * <p>依赖 Flowable 内部表 {@code ACT_HI_PROCINST} 与 {@code ACT_HI_TASKINST}。
 * 表名与列名在 Flowable 6.x 中跨数据库一致，表名在此硬编码，不做表前缀注入。
 * LIKE 转义不写显式 ESCAPE 子句，依赖 H2/MySQL/PostgreSQL 默认转义符均为
 * 反斜杠的一致性（见 ADR-0013 实现演化节）。</p>
 *
 * <p>本类为内部实现细节（唯一消费者为同包 {@link TaskQueryModule}），
 * 不属公开 API（ADR-0015）。</p>
 *
 * @author flowable-plus
 */
final class PreciseDoneQuerySqlBuilder {

    private PreciseDoneQuerySqlBuilder() {
    }

    /**
     * 构建精确已办查询 SQL：查出"存在已完成 assignee 任务"的流程实例。
     *
     * <p>基础条件是当前用户在该流程实例中至少完成过 1 个任务
     * （EXISTS 子查询），随后按查询条件追加动态过滤。</p>
     *
     * @param userId 用户 ID，不可为 null 或空
     * @param query  查询条件，可为 null（按空条件处理）
     * @return 完整 SQL 字符串
     */
    static String build(String userId, TaskQueryDTO query) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        StringBuilder sql = new StringBuilder(512);
        sql.append("SELECT RES.* FROM ACT_HI_PROCINST RES WHERE EXISTS (")
           .append("SELECT 1 FROM ACT_HI_TASKINST T WHERE ")
           .append("T.PROC_INST_ID_ = RES.ID_ AND T.ASSIGNEE_ = '")
           .append(escapeSql(userId))
           .append("' AND T.END_TIME_ IS NOT NULL)");

        // 动态过滤条件
        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            sql.append(" AND RES.PROC_DEF_ID_ LIKE '")
               .append(escapeSql(query.getProcessDefinitionKey()))
               .append(":%'");
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            // 不写显式 ESCAPE 子句：H2/MySQL/PostgreSQL 的 LIKE 默认转义符均为反斜杠，
            // escapeLike 产出的 \% \_ \\ 在三库行为一致（见 ADR-0013 实现演化节）
            sql.append(" AND RES.BUSINESS_KEY_ LIKE '%")
               .append(escapeLike(query.getKeyword()))
               .append("%'");
        }
        if (query.getBeginDate() != null) {
            sql.append(" AND RES.END_TIME_ >= '")
               .append(formatDate(query.getBeginDate()))
               .append("'");
        }
        if (query.getEndDate() != null) {
            sql.append(" AND RES.END_TIME_ <= '")
               .append(formatDate(query.getEndDate()))
               .append("'");
        }

        sql.append(" ORDER BY RES.END_TIME_ DESC");
        return sql.toString();
    }

    // ======================== 转义工具（安全关键） ========================

    /**
     * 转义 SQL 字符串中的单引号（{@code ' → ''}）。
     *
     * <p>用于 Native SQL 拼接时防止 SQL 注入。</p>
     */
    static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    /**
     * 转义 SQL LIKE 表达式中的特殊字符。
     *
     * <p>在 {@link #escapeSql(String)} 基础上额外转义 {@code %} 和 {@code _}，
     * 防止用户输入被误解为 SQL LIKE 通配符。配合各库默认的反斜杠转义符生效。</p>
     */
    static String escapeLike(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                    .replace("'", "''");
    }

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    static String formatDate(Date date) {
        return DATE_FORMAT.get().format(date);
    }
}
