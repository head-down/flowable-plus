package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.dto.TaskQueryDTO;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PreciseDoneQuerySqlBuilder} 单元测试。
 *
 * <p>覆盖 SQL 生成形状与安全关键转义逻辑（SQL 注入 / LIKE 通配符注入）。</p>
 */
public class PreciseDoneQuerySqlBuilderTest {

    private static final String BASE_SQL =
            "SELECT RES.* FROM ACT_HI_PROCINST RES WHERE EXISTS ("
            + "SELECT 1 FROM ACT_HI_TASKINST T WHERE "
            + "T.PROC_INST_ID_ = RES.ID_ AND T.ASSIGNEE_ = 'user1' AND T.END_TIME_ IS NOT NULL)";

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullUserId() {
        assertThatThrownBy(() -> PreciseDoneQuerySqlBuilder.build(null, new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    public void testRejectEmptyUserId() {
        assertThatThrownBy(() -> PreciseDoneQuerySqlBuilder.build("", new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    public void testNullQueryTreatedAsEmpty() {
        assertThat(PreciseDoneQuerySqlBuilder.build("user1", null))
                .isEqualTo(BASE_SQL + " ORDER BY RES.END_TIME_ DESC");
    }

    // ======================== SQL 生成 ========================

    @Test
    public void testBuildBasicQuery() {
        assertThat(PreciseDoneQuerySqlBuilder.build("user1", new TaskQueryDTO()))
                .isEqualTo(BASE_SQL + " ORDER BY RES.END_TIME_ DESC");
    }

    @Test
    public void testBuildWithAllFilters() throws Exception {
        TaskQueryDTO query = new TaskQueryDTO();
        query.setProcessDefinitionKey("leave");
        query.setKeyword("借款");
        query.setBeginDate(parseDate("2024-01-01 00:00:00"));
        query.setEndDate(parseDate("2024-01-31 23:59:59"));

        String sql = PreciseDoneQuerySqlBuilder.build("user1", query);

        assertThat(sql).isEqualTo(
                BASE_SQL
                + " AND RES.PROC_DEF_ID_ LIKE 'leave:%'"
                + " AND RES.BUSINESS_KEY_ LIKE '%借款%'"
                + " AND RES.END_TIME_ >= '2024-01-01 00:00:00'"
                + " AND RES.END_TIME_ <= '2024-01-31 23:59:59'"
                + " ORDER BY RES.END_TIME_ DESC");
    }

    @Test
    public void testBuildEscapesUserId() {
        // 用户 ID 含单引号：必须转义为 ''，否则可注入任意 SQL
        String sql = PreciseDoneQuerySqlBuilder.build("O'Brien", new TaskQueryDTO());
        assertThat(sql).contains("AND T.ASSIGNEE_ = 'O''Brien'");
        assertThat(sql).doesNotContain("= 'O'Brien'");
    }

    @Test
    public void testBuildEscapesKeyword() {
        // keyword 含 LIKE 通配符与转义符：% _ \ 均需转义
        TaskQueryDTO query = new TaskQueryDTO();
        query.setKeyword("50%_off\\foo'");

        String sql = PreciseDoneQuerySqlBuilder.build("user1", query);

        assertThat(sql).contains("LIKE '%50\\%\\_off\\\\foo''%'");
    }

    // ======================== 转义工具 ========================

    @Test
    public void testEscapeSql() {
        assertThat(PreciseDoneQuerySqlBuilder.escapeSql("O'Brien")).isEqualTo("O''Brien");
        assertThat(PreciseDoneQuerySqlBuilder.escapeSql("plain")).isEqualTo("plain");
        assertThat(PreciseDoneQuerySqlBuilder.escapeSql(null)).isEmpty();
    }

    @Test
    public void testEscapeLike() {
        // 逻辑输入: 50%_off\foo'  → 输出: 50\%\_off\\foo''
        assertThat(PreciseDoneQuerySqlBuilder.escapeLike("50%_off\\foo'"))
                .isEqualTo("50\\%\\_off\\\\foo''");
        // % 与 _ 单独转义
        assertThat(PreciseDoneQuerySqlBuilder.escapeLike("100%_complete"))
                .isEqualTo("100\\%\\_complete");
        // 反斜杠自身优先转义
        assertThat(PreciseDoneQuerySqlBuilder.escapeLike("a\\b"))
                .isEqualTo("a\\\\b");
        assertThat(PreciseDoneQuerySqlBuilder.escapeLike(null)).isEmpty();
    }

    // ======================== 日期格式化 ========================

    @Test
    public void testFormatDate() throws Exception {
        Date date = parseDate("2024-06-15 08:30:45");
        assertThat(PreciseDoneQuerySqlBuilder.formatDate(date))
                .isEqualTo("2024-06-15 08:30:45");
    }

    private Date parseDate(String value) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
    }
}
