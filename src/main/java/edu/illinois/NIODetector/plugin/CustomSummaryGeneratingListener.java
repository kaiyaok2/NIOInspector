package edu.illinois.NIODetector.plugin;

import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import org.junit.platform.engine.TestExecutionResult;

import java.util.HashMap;
import java.util.Map;

public class CustomSummaryGeneratingListener extends SummaryGeneratingListener {
    private final Map<String, Boolean> testPassStatus = new HashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        super.testPlanExecutionStarted(testPlan);
        testPassStatus.clear();
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        super.executionFinished(testIdentifier, testExecutionResult);
        String testUniqueId = testIdentifier.getUniqueId();
        if (!testPassStatus.containsKey(testUniqueId)) {
            testPassStatus.put(testUniqueId, testExecutionResult.getStatus() == TestExecutionResult.Status.SUCCESSFUL);
        }
    }

    public Map<String, Boolean> getTestPassStatus() {
        return testPassStatus;
    }
}
