package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5 —— 决策载荷的三态与四段定型外壳守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E5}）。
 *
 * <p><b>承哪些推入项</b>：ADR-0042 第 6 节「载荷同型」—— 四段定型外壳，且「<b>未声明的段 = 缺席</b>」
 * 与「<b>已声明但该来源为空 = 空集合</b>」两态<b>序列化后仍可区分</b>。</p>
 *
 * <p><b>纯值，零引擎</b>：断言对象是类型的字段形状与 JSON 形态。</p>
 */
class DecisionPayloadTest {

    /** 四段的字段名（对账清单；序列化键即字段名） */
    private static final List<String> DECLARED_SEGMENT_NAMES = Arrays.asList(
            "processVariables", "taskVariables", "taskMetadata", "processInstanceMetadata");

    /** 四段的字段类型（对账清单） */
    private static final Map<String, Class<?>> DECLARED_SEGMENT_TYPES = declaredSegmentTypes();

    /** 序列化器（默认配置：空值照发 {@code null}，三态才可分） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("载荷三态可区分：未声明段序列化为 null、声明但空序列化为空集合")
    void absentSegmentSerializesAsNullAndDeclaredEmptyAsEmptyCollection() throws Exception {
        final DecisionPayload allAbsent = new DecisionPayload(null, null, null, null);
        final DecisionPayload declaredEmpty = new DecisionPayload(
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                new TaskMetadata(null, null, null, null, null),
                new ProcessInstanceMetadata(null, null, null, null, null));

        // 防空转：被断言为「已声明」的 fixture 必须真的声明了段，否则本断言恒真
        assertThat(declaredEmpty.getProcessVariables()).as("fixture 的变量段必须真的是「已声明但空」").isNotNull();
        assertThat(declaredEmpty.getTaskVariables()).isNotNull();
        assertThat(declaredEmpty.getTaskMetadata()).isNotNull();
        assertThat(declaredEmpty.getProcessInstanceMetadata()).isNotNull();

        final JsonNode absentNode = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(allAbsent));
        final JsonNode declaredNode = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(declaredEmpty));

        for (final String segmentName : DECLARED_SEGMENT_NAMES) {
            assertThat(absentNode.get(segmentName).isNull())
                    .as("未声明段 %s 必须序列化为 JSON null（缺席）", segmentName)
                    .isTrue();
            assertThat(declaredNode.get(segmentName).isNull())
                    .as("已声明段 %s 不得序列化为 null（那会与缺席折叠成同一态）", segmentName)
                    .isFalse();
        }

        // 变量段：声明但空 ⇒ 空集合；元数据段：声明但空 ⇒ 空的小对象
        assertThat(declaredNode.get("processVariables").isObject()).isTrue();
        assertThat(declaredNode.get("processVariables").size()).as("声明但空 = 空集合").isZero();
        assertThat(declaredNode.get("taskVariables").size()).isZero();
        assertThat(declaredNode.get("taskMetadata").isObject()).isTrue();
        assertThat(declaredNode.get("taskMetadata").get("taskId").isNull()).isTrue();
        assertThat(declaredNode.get("processInstanceMetadata").isObject()).isTrue();

        assertThat(OBJECT_MAPPER.writeValueAsString(allAbsent))
                .as("两态序列化后必须可区分（缺席出 null、声明但空出 {}）")
                .contains("\"processVariables\":null")
                .isNotEqualTo(OBJECT_MAPPER.writeValueAsString(declaredEmpty));
    }

    @Test
    @DisplayName("载荷恰四段定型外壳：字段名与字段类型逐一相等")
    void segmentsAreExactlyFourTypedShells() {
        final List<Field> fields = Arrays.asList(DecisionPayload.class.getDeclaredFields());

        assertThat(fields.stream().map(Field::getName).collect(Collectors.toList()))
                .as("载荷恰四段、名字与清单逐个对上")
                .containsExactlyInAnyOrderElementsOf(DECLARED_SEGMENT_NAMES);

        for (final Field field : fields) {
            assertThat(field.getType())
                    .as("段 %s 的类型必须是定型外壳字段", field.getName())
                    .isEqualTo(DECLARED_SEGMENT_TYPES.get(field.getName()));
        }
    }

    /**
     * 四段的字段类型对账清单（键 = 字段名，值 = 声明类型）。
     *
     * @return 字段名 → 类型
     */
    private static Map<String, Class<?>> declaredSegmentTypes() {
        final Map<String, Class<?>> types = new LinkedHashMap<>();
        types.put("processVariables", Map.class);
        types.put("taskVariables", Map.class);
        types.put("taskMetadata", TaskMetadata.class);
        types.put("processInstanceMetadata", ProcessInstanceMetadata.class);
        return Collections.unmodifiableMap(types);
    }
}
