package edu.illinois.NIODetector.plugin;

import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.TestExecutionResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom listener to support tracking all tests executed (instead of failed tests only)
 */
public class CustomSummaryGeneratingListener extends SummaryGeneratingListener {

    // Stores the execution status of each executed test
    private final Map<String, Boolean> testPassStatus = new HashMap<>();

    /**
     * Make sure the Map is clean when a run starts
     *
     * @param testPlan The test plan being executed.
     */
    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        super.testPlanExecutionStarted(testPlan);
        testPassStatus.clear();
    }

    /**
     * Updates the map with the status of the test upon finishing execution
     *
     * @param testIdentifier     The identifier of the finished test.
     * @param testExecutionResult The result of the finished test execution.
     */
    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        super.executionFinished(testIdentifier, testExecutionResult);
        String testUniqueId = testIdentifier.getUniqueId();
        // Store the execution status of the test identified by its unique ID
        if (!testPassStatus.containsKey(testUniqueId)) {
            testPassStatus.put(testUniqueId, testExecutionResult.getStatus() == TestExecutionResult.Status.SUCCESSFUL);
        }
    }

    /**
     * Retrieves the map containing the execution status of each test.
     *
     * @return The test status map for the current run.
     */
    public Map<String, Boolean> getTestPassStatus() {
        return testPassStatus;
    }
}
