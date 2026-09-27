package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E19 —— stub 契约（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E19}）。
 *
 * <p><b>承哪些推入项</b>：{@code #38} 第 3 项（<b>stub 是契约、recorded 不进 v1</b>：默认 Provider 暴露
 * <b>可注入 Transport 缝</b> + 固定 fixture）+ {@code #10} + {@code #13}「v1 可达性」（<b>测试内</b>须提供
 * 最小 {@code SYSTEM} 直提生产者桩，<b>且同时覆盖 {@code USER}</b>）+ ADR-0042 第 7 节。</p>
 *
 * <p><b>recorded 无栖身处是结构保证</b>：本模块的测试树不建 {@code src/test/resources}，fixture 一律是
 * Java 常量 —— 故「recorded 不进 v1」不是纪律而是形态。</p>
 */
class DecisionStubContractTest {

    /** 测试树根（surefire 工作目录 = 模块 basedir） */
    private static final Path TEST_SOURCE_ROOT = Paths.get("src", "test");

    /** 资源目录（recorded fixture 唯一可能的栖身处） */
    private static final Path TEST_RESOURCE_ROOT = Paths.get("src", "test", "resources");

    /** fixture 常量的两个承载位（产出 / 显式不产出） */
    private static final List<String> FIXTURE_FIELD_NAMES = Arrays.asList("PRODUCED_FIXTURE", "DECLINED_FIXTURE");

    @Test
    @DisplayName("默认 Provider 暴露可注入的 Transport 缝：注入的实现被真实使用")
    void defaultProviderAcceptsInjectedTransport() {
        final StubDecisionTransport injected = new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
        final DecisionProvider provider = new DefaultDecisionProvider(injected, targetKey -> null);

        assertThat(provider.send(request()).getFailureKind()).as("注入的 Transport 缝可用").isNull();
        assertThat(injected.getCallCount())
                .as("出站调用确实经由注入的实现（不是某个内建实现）")
                .isEqualTo(1);
        assertThat(injected.lastRequestBodyText()).as("注入实现能观测到请求体（无网回放的基础）").isNotNull();
    }

    @Test
    @DisplayName("fixture 是惰性 Java 常量、不是录制文件")
    void fixturesAreInertJavaConstantsNotRecordedFiles() {
        for (final String fieldName : FIXTURE_FIELD_NAMES) {
            final Field field = fieldOf(StubDecisionTransport.class, fieldName);
            assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()))
                    .as("fixture %s 必须是静态最终字段（编译期常量）", fieldName)
                    .isTrue();
            assertThat(field.getType()).as("fixture %s 的形态是裸串常量", fieldName).isEqualTo(String.class);
        }
        assertThat(nonJavaFilesUnderTestTree())
                .as("测试树内不得有任何非 Java 文件 ⇒ recorded fixture 无栖身处")
                .isEmpty();
    }

    @Test
    @DisplayName("SYSTEM 直提桩：v1 可达性 —— SYSTEM 主体只在推面可达，落 B 列且主体类型为 SYSTEM")
    void systemDirectStubProducesSubjectTypeSystem() throws IOException {
        assertDirectStubSubjectType(DecisionSubjectType.SYSTEM);
    }

    @Test
    @DisplayName("USER 直提桩：v1 可达性 —— USER 主体同样须有最小生产者桩")
    void userDirectStubProducesSubjectTypeUser() throws IOException {
        assertDirectStubSubjectType(DecisionSubjectType.USER);
    }

    @Test
    @DisplayName("recorded 无栖身处：extension 测试树不建资源目录")
    void noRecordedFixtureResourceExists() {
        assertThat(Files.exists(TEST_RESOURCE_ROOT))
                .as("本模块测试树不建 src/test/resources（「recorded 不进 v1」的结构保证）")
                .isFalse();
    }

    // ======================== 驱动辅助 ========================

    /**
     * 驱动一条直提桩并断言落行主体类型（B 列：出处组全 null）。
     *
     * @param subjectType 桩的主体类型（{@code SYSTEM} / {@code USER}）
     */
    private static void assertDirectStubSubjectType(final DecisionSubjectType subjectType) throws IOException {
        final Task anchor = mock(Task.class);
        when(anchor.getId()).thenReturn(DecisionFixtures.TASK_ID);
        when(anchor.getProcessInstanceId()).thenReturn(DecisionFixtures.PROCESS_INSTANCE_ID);
        when(anchor.getTaskDefinitionKey()).thenReturn(DecisionFixtures.NODE_ID);
        final TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(anchor);
        final TaskService taskService = mock(TaskService.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        final ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
        when(processInstanceQuery.processInstanceId(anyString())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.count()).thenReturn(1L);
        final RuntimeService runtimeService = mock(RuntimeService.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        final MultiInstanceDetector detector = mock(MultiInstanceDetector.class);
        when(detector.isMultiInstance(any())).thenReturn(false);
        when(detector.isRuntimeMultiInstance(any())).thenReturn(false);
        when(detector.isInitiatorDecisionTask(any())).thenReturn(false);

        new DefaultSuggestionSubmissionService(detector, taskService, runtimeService,
                new DecisionObservationEmitter(null, Collections.emptyList()), true)
                .submit(DecisionFixtures.directSubmissionOf(subjectType));

        final JsonNode payload = DecisionFixtures.evidenceJson(capturedRow(taskService));
        assertThat(DecisionFixtures.enumValue(payload.get("subjectType"), DecisionSubjectType.class))
                .as("v1 可达性：%s 直提桩必须产出同值主体类型", subjectType)
                .isEqualTo(subjectType);
        assertThat(payload.get("provider").isNull() && payload.get("chainStage").isNull()
                && payload.get("degraded").isNull())
                .as("直提桩落 B 列：出处组全 null")
                .isTrue();
        assertThat(DecisionFixtures.enumValue(payload.get("suggestedAction"), ApprovalAction.class))
                .isEqualTo(ApprovalAction.AGREE);
    }

    /** 本段落下的唯一一行证据（整行文本）。 */
    private static String capturedRow(final TaskService taskService) {
        final ArgumentCaptor<String> rows = ArgumentCaptor.forClass(String.class);
        verify(taskService, atLeast(0)).addComment(anyString(), anyString(), anyString(), rows.capture());
        assertThat(rows.getAllValues()).as("直提桩应当恰好落一行证据").hasSize(1);
        return rows.getAllValues().get(0);
    }

    /** 测试树内全部非 Java 文件（recorded fixture 的栖身处判据）。 */
    private static List<Path> nonJavaFilesUnderTestTree() {
        if (!Files.exists(TEST_SOURCE_ROOT)) {
            return Collections.emptyList();
        }
        final List<Path> nonJava = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(TEST_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".java"))
                    .forEach(nonJava::add);
        } catch (IOException unreadableTestTree) {
            throw new IllegalStateException("无法枚举测试树（recorded 无栖身处的判据依赖它）", unreadableTestTree);
        }
        return nonJava;
    }

    /** 按名取声明字段。 */
    private static Field fieldOf(final Class<?> owner, final String fieldName) {
        try {
            return owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException missingFixture) {
            throw new IllegalStateException("stub 契约要求 fixture 常量在场：" + fieldName, missingFixture);
        }
    }

    /** 构造一份最小出站请求（决策目标 + 四段全声明载荷）。 */
    private static DecisionProviderRequest request() {
        final DecisionTarget target = new DecisionTarget() {

            @Override
            public String key() {
                return DecisionFixtures.PROVIDER;
            }

            @Override
            public String url() {
                return "https://decision.example.invalid/v1/suggest";
            }
        };
        final DecisionPayload payload = new DecisionPayload(Collections.emptyMap(), Collections.emptyMap(),
                new TaskMetadata(DecisionFixtures.TASK_ID, "任务", DecisionFixtures.NODE_ID, null, null),
                new ProcessInstanceMetadata(DecisionFixtures.PROCESS_INSTANCE_ID, "definition-key-static",
                        null, null, null));
        return new DecisionProviderRequest(target, payload);
    }
}
