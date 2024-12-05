package edu.illinois.NIOInspector.plugin.util.detection;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import org.junit.platform.engine.TestExecutionResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

public class CustomSummaryGeneratingListenerTest {

    @Test
    public void testTestPlanExecutionStarted_ClearsTestStatusMap() {
        CustomSummaryGeneratingListener listener = new CustomSummaryGeneratingListener();
        TestPlan mockTestPlan = mock(TestPlan.class);
        listener.getTestPassStatus().put("test1", true);

        listener.testPlanExecutionStarted(mockTestPlan);

        assertTrue(listener.getTestPassStatus().isEmpty(), "Test pass status map should be empty after test plan execution started");
    }

    @Test
    public void testExecutionFinished_UpdatesTestStatus() {
        CustomSummaryGeneratingListener listener = new CustomSummaryGeneratingListener();
        TestIdentifier mockTestIdentifier = mock(TestIdentifier.class);
        TestExecutionResult mockTestExecutionResult = mock(TestExecutionResult.class);
        String testId = "test1";
        
        when(mockTestIdentifier.getUniqueId()).thenReturn(testId);
        when(mockTestExecutionResult.getStatus()).thenReturn(TestExecutionResult.Status.SUCCESSFUL);

        listener.executionFinished(mockTestIdentifier, mockTestExecutionResult);

        Map<String, Boolean> statusMap = listener.getTestPassStatus();
        assertEquals(1, statusMap.size(), "Status map should contain one entry");
        assertTrue(statusMap.containsKey(testId), "Status map should contain the test ID");
        assertTrue(statusMap.get(testId), "Test status should be successful");
    }

    @Test
    public void testExecutionFinished_DoesNotOverwriteExistingTestStatus() {
        CustomSummaryGeneratingListener listener = new CustomSummaryGeneratingListener();
        TestIdentifier mockTestIdentifier = mock(TestIdentifier.class);
        TestExecutionResult mockTestExecutionResult = mock(TestExecutionResult.class);
        String testId = "test1";

        listener.getTestPassStatus().put(testId, false);
        
        when(mockTestIdentifier.getUniqueId()).thenReturn(testId);
        when(mockTestExecutionResult.getStatus()).thenReturn(TestExecutionResult.Status.SUCCESSFUL);

        listener.executionFinished(mockTestIdentifier, mockTestExecutionResult);

        Map<String, Boolean> statusMap = listener.getTestPassStatus();
        assertEquals(1, statusMap.size(), "Status map should contain one entry");
        assertTrue(statusMap.containsKey(testId), "Status map should contain the test ID");
        assertFalse(statusMap.get(testId), "Test status should not be overwritten");
    }

    @Test
    public void testGetTestPassStatus_ReturnsCorrectMap() {
        CustomSummaryGeneratingListener listener = new CustomSummaryGeneratingListener();
        TestIdentifier mockTestIdentifier1 = mock(TestIdentifier.class);
        TestIdentifier mockTestIdentifier2 = mock(TestIdentifier.class);
        TestExecutionResult mockTestExecutionResult1 = mock(TestExecutionResult.class);
        TestExecutionResult mockTestExecutionResult2 = mock(TestExecutionResult.class);
        String testId1 = "test1";
        String testId2 = "test2";
        
        when(mockTestIdentifier1.getUniqueId()).thenReturn(testId1);
        when(mockTestExecutionResult1.getStatus()).thenReturn(TestExecutionResult.Status.SUCCESSFUL);
        listener.executionFinished(mockTestIdentifier1, mockTestExecutionResult1);

        when(mockTestIdentifier2.getUniqueId()).thenReturn(testId2);
        when(mockTestExecutionResult2.getStatus()).thenReturn(TestExecutionResult.Status.FAILED);
        listener.executionFinished(mockTestIdentifier2, mockTestExecutionResult2);

        Map<String, Boolean> statusMap = listener.getTestPassStatus();

        assertEquals(2, statusMap.size(), "Status map should contain two entries");
        assertTrue(statusMap.containsKey(testId1), "Status map should contain the first test ID");
        assertTrue(statusMap.containsKey(testId2), "Status map should contain the second test ID");
        assertTrue(statusMap.get(testId1), "First test status should be successful");
        assertFalse(statusMap.get(testId2), "Second test status should be failed");
    }
}