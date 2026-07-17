package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.ProcessEndedEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProcessEndDetector 单元测试。
 *
 * @author flowable-plus
 */
public class ProcessEndDetectorTest {

    private RuntimeService mockRuntimeService;
    private HistoryService mockHistoryService;
    private EventPublisher mockEventPublisher;
    private ProcessEndDetector detector;

    @BeforeEach
    void setUp() {
        mockRuntimeService = mock(RuntimeService.class);
        mockHistoryService = mock(HistoryService.class);
        mockEventPublisher = mock(EventPublisher.class);
        detector = new ProcessEndDetector(mockRuntimeService, mockHistoryService, mockEventPublisher);
    }

    @Test
    void shouldNotPublishWhenProcessStillRunning() {
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(mock(ProcessInstance.class));
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        detector.checkAndPublish("pi-001");

        verify(mockEventPublisher, never()).publish(any());
    }

    @Test
    void shouldPublishProcessEndedWhenProcessFinished() {
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(null);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getProcessDefinitionKey()).thenReturn("leave");
        when(hpi.getBusinessKey()).thenReturn("biz-001");
        when(hpi.getEndTime()).thenReturn(new Date());
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        detector.checkAndPublish("pi-001");

        verify(mockEventPublisher).publish(any(ProcessEndedEvent.class));
    }

    @Test
    void shouldNotPublishWhenNoHistoricInstance() {
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(null);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(null);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        detector.checkAndPublish("pi-001");

        verify(mockEventPublisher, never()).publish(any());
    }
}
