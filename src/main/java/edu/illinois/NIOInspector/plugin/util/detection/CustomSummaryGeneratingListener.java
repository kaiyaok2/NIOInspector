package edu.illinois.NIOInspector.plugin.util.detection;

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

    // Unique ID of the test currently executing (used to attribute failures
    // that surface in background threads to the test that triggered them)
    private volatile String currentTestUniqueId;

    // Unique ID of the test that finished most recently (fallback attribution:
    // a polluted background thread often dies just after its test returns)
    private volatile String lastFinishedTestUniqueId;

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
     * Tracks the currently executing test
     *
     * @param testIdentifier The identifier of the started test.
     */
    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        super.executionStarted(testIdentifier);
        if (testIdentifier.isTest()) {
            currentTestUniqueId = testIdentifier.getUniqueId();
        }
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
        if (testIdentifier.isTest()) {
            lastFinishedTestUniqueId = testUniqueId;
        }
        if (testUniqueId.equals(currentTestUniqueId)) {
            currentTestUniqueId = null;
        }
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

    /**
     * Retrieves the unique ID of the test currently executing, if any.
     *
     * @return The unique ID of the running test, or null if none is running.
     */
    public String getCurrentTestUniqueId() {
        return currentTestUniqueId;
    }

    /**
     * Retrieves the unique ID of the most recently finished test, if any.
     *
     * @return The unique ID of the last finished test, or null if none finished yet.
     */
    public String getLastFinishedTestUniqueId() {
        return lastFinishedTestUniqueId;
    }
}
