package edu.illinois.NIOInspector.plugin.util.detection;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.TestIdentifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * A JUnit test runner in an isolated classloader
 */
public class ClassLoaderIsolatedTestRunner {

    private static final Logger logger = LoggerFactory.getLogger(ClassLoaderIsolatedTestRunner.class);

    /**
     * Disallow construction at all from wrong ClassLoader
     *
     * @throws MojoExecutionException
     */
    public ClassLoaderIsolatedTestRunner() throws MojoExecutionException {
        ensureLoadedInIsolatedClassLoader(this);
    }

    /**
     * Runs the tests reflectively using the provided class loader.
     *
     * @param testClasses the list of test classes to run
     * @param classStringToMethodsMap the mapping between test classes and selected methods to run
     * @param classLoader the class loader loaded with test classes and all dependencies
     * @param numReruns user-configured number of times to rerun the tests
     * @throws MojoExecutionException
     */
    public void runInvokedReflectively(List<String> testClasses, Map<String, List<String>> classStringToMethodsMap,
        ClassLoader classLoader, int numReruns) throws MojoExecutionException {

        // Make sure no elements come from other (e.g. system) classloaders
        ensureLoadedInIsolatedClassLoader(this);

        // Load classes and methods (if selected)
        List<Class<?>> classesToRunAllTests = new ArrayList<>();
        List<Class<?>> classesToRunSelectedTests = new ArrayList<>();
        Map<Class<?>, List<String>> classToMethodsMap = new HashMap<>();
        for (int i = 0; i < testClasses.size(); i++) {
            String testClassString = testClasses.get(i);
            try {
                Class<?> testClass = Class.forName(testClassString, true, classLoader);
                if (classStringToMethodsMap.containsKey(testClassString)) {
                    classToMethodsMap.put(testClass, classStringToMethodsMap.get(testClassString));
                    if (!classesToRunSelectedTests.contains(testClass)) {
                        classesToRunSelectedTests.add(testClass);
                    }
                } else {
                    classesToRunAllTests.add(testClass);
                }
            } catch (Exception | Error e) {
                String msg = "The [" + testClassString + "] test class is not found or failed to initialize.";
                logger.warn(msg);
                logger.warn(e.toString());
                e.printStackTrace();
                continue;
            }
        }

        // Run JUnit 4 or 5 tests using either Jupiter or Vintage Engine
        runJUnitTests(classesToRunAllTests, classesToRunSelectedTests, classToMethodsMap, classLoader, numReruns);
    }

    /**
     * Runs JUnit 4/5 tests.
     *
     * @param classesToRunAllTests the list of test classes where all methods shall be run
     * @param classesToRunSelectedTests the array of test classes where selected methods shall be run
     * @param classToMethodsMap map from a test class to the selected test methods to run
     * @param classLoader the class loader loaded with test classes and all dependencies
     * @param numReruns user-configured number of times to rerun the tests
     * @throws MojoExecutionException
     */
    private void runJUnitTests(List<Class<?>> classesToRunAllTests, List<Class<?>> classesToRunSelectedTests,
        Map<Class<?>, List<String>> classToMethodsMap, ClassLoader classLoader, int numReruns) throws MojoExecutionException {

        // Sanity check
        Thread.currentThread().setContextClassLoader(classLoader);
        Launcher launcher = null;

        // Use reflection to invoke create() method of LauncherFactory to ensure it picks up the correct classloader
        try {
            Method createMethod = LauncherFactory.class.getDeclaredMethod("create");
            createMethod.setAccessible(true);
            launcher = (Launcher) createMethod.invoke(null);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new MojoExecutionException("Error invoking ClassLoaderIsolatedTestRunner", e);
        }
        ensureLoadedInIsolatedClassLoader(launcher);

        // Custom listener to track test pass status
        CustomSummaryGeneratingListener listener = new CustomSummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        // Some NIO tests self-pollute state that only makes *background* threads
        // fail in reruns (e.g. an assertion inside a thread-pool worker), which
        // JUnit never observes. Track uncaught background-thread exceptions and
        // attribute them to the test running at that moment, so such tests can
        // still be reported under the same passes-first/fails-later principle.
        final Map<String, Integer> backgroundFailuresThisRun = new java.util.concurrent.ConcurrentHashMap<>();
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Prefer the currently running test; fall back to the most recently
            // finished one (a polluted worker thread often dies just after its
            // test method has already returned).
            String attributedTest = listener.getCurrentTestUniqueId();
            if (attributedTest == null) {
                attributedTest = listener.getLastFinishedTestUniqueId();
            }
            if (attributedTest != null) {
                String testString = extractTestMethod(attributedTest);
                backgroundFailuresThisRun.merge(testString, 1, Integer::sum);
                logger.warn("Failing Test: " + testString + " (uncaught failure in background thread " +
                    thread.getName() + ")");
                logger.warn("Failure message: ", throwable);
            } else {
                logger.warn("Uncaught exception in background thread " + thread.getName() +
                    " (no test attributable)", throwable);
            }
        });

        // Select classes or methods to run
        LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> testClass : classesToRunAllTests) {
            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
        }
        for (Class<?> testClass : classesToRunSelectedTests) {
            for (String method : classToMethodsMap.get(testClass)) {
                requestBuilder.selectors(DiscoverySelectors.selectMethod(testClass, method));
            }
        }

        // First Run
        logger.info("");
        logger.info("====================Starting the Initial Run of Test====================");
        logger.info("");
        launcher.execute(requestBuilder.build());
        final Map<String, Boolean> testStatusInFirstRun = new HashMap<>(listener.getTestPassStatus());
        TestExecutionSummary summary = listener.getSummary();
        final long testsStartedInFirstRun = summary.getTestsStartedCount();
        final Map<String, Integer> backgroundFailuresInFirstRun = new HashMap<>(backgroundFailuresThisRun);
        printSummary(summary);

        // Reruns
        Map<String, Integer> flakyTests = new HashMap<>();
        Map<String, Integer> NIOTests = new HashMap<>();
        Map<String, Integer> NDTests = new HashMap<>();
        Map<String, Integer> backgroundFlakyTests = new HashMap<>();
        for (int i = 0; i < numReruns; i++) {
            logger.info("");
            logger.info("=======================Starting Rerun #" + (i + 1) + "=========================");
            logger.info("");
            backgroundFailuresThisRun.clear();
            launcher.execute(requestBuilder.build());
            summary = listener.getSummary();
            summary.getFailures().forEach(failure -> {
                TestIdentifier testIdentifier = failure.getTestIdentifier();
                String testUniqueId = testIdentifier.getUniqueId();
                String testString = extractTestMethod(testUniqueId);
                if (testStatusInFirstRun.containsKey(testUniqueId) &&
                    testStatusInFirstRun.get(testUniqueId)) {
                        // Test passed in the first iteration but failed in later iteration
                        if (flakyTests.containsKey(testString)) {
                            flakyTests.put(testString, flakyTests.get(testString) + 1);
                        } else {
                            flakyTests.put(testString, 1);
                        }
                }
            });
            // Tests whose rerun failures surface only in background threads:
            // count them when the first run had no such background failure
            for (String testString : backgroundFailuresThisRun.keySet()) {
                if (!backgroundFailuresInFirstRun.containsKey(testString)) {
                    backgroundFlakyTests.merge(testString, 1, Integer::sum);
                }
            }
            printSummary(summary);
        }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);

        // Tests flagged only through the background-thread channel. Observation
        // on this channel is inherently lossy (an async failure can land between
        // runs and miss attribution), so presence in any rerun - with a clean
        // first run - is reported as possible NIO rather than requiring failure
        // in every rerun.
        Map<String, Integer> backgroundOnlyFlakyTests = new HashMap<>();
        for (Map.Entry<String, Integer> entry : backgroundFlakyTests.entrySet()) {
            if (!flakyTests.containsKey(entry.getKey())) {
                backgroundOnlyFlakyTests.put(entry.getKey(), entry.getValue());
            }
        }

        // Log final results (possible NIO tests)
        for (Map.Entry<String, Integer> entry : flakyTests.entrySet()) {
            // Check if a test is not failing in all reruns
            if (entry.getValue() < numReruns) {
                NDTests.put(entry.getKey(), entry.getValue());
            } else {
                NIOTests.put(entry.getKey(), entry.getValue());
            }
        }
        NIOTests.putAll(backgroundOnlyFlakyTests);
        flakyTests.putAll(backgroundOnlyFlakyTests);
        logger.info("");
        logger.info("=========================Final Results=========================");
        logger.info("");
        if (testsStartedInFirstRun == 0) {
            // Do not report "No Flaky Tests Found" when nothing actually ran -
            // that would be a false negative (e.g. stale or missing test classes).
            logger.error("No tests were executed - ensure the test classes are compiled " +
                "(e.g. run `mvn process-test-classes`) and the selected tests exist.");
        } else if (flakyTests.isEmpty()) {
            logger.info("No Flaky Tests Found");
        } else {
            if (!NIOTests.isEmpty()) {
                logger.error("Number of Possible NIO Test(s) Found: " + NIOTests.size());
                for (Map.Entry<String, Integer> NIOEntry : NIOTests.entrySet()) {
                    String channel = backgroundOnlyFlakyTests.containsKey(NIOEntry.getKey())
                        ? " via background-thread failures" : "";
                    logger.error(NIOEntry.getKey() + " (passed in the initial run but failed in " +
                        NIOEntry.getValue() + " out of " + numReruns + " reruns" + channel + ")");
                }
            }
            if (!NDTests.isEmpty()) {
                logger.warn("Number of Non-deterministic Flaky Test(s) Found: " + NDTests.size());
                for (Map.Entry<String, Integer> NDEntry : NDTests.entrySet()) {
                    String channel = backgroundOnlyFlakyTests.containsKey(NDEntry.getKey())
                        ? " via background-thread failures" : "";
                    logger.warn(NDEntry.getKey() + " (passed in the initial run but failed in " +
                        NDEntry.getValue() + " out of " + numReruns + " reruns" + channel + ")");
                }
            }
        }
        
    }

    /**
     * Prints the test execution summary produced by the JUnit Jupiter / Vintage engine
     * @param summary The test execution summary to logged.
     */
    private void printSummary(TestExecutionSummary summary) {
        summary.printTo(new PrintWriter(System.out));
        summary.getFailures().forEach(failure -> {
            logger.warn("Failing Test: " + extractTestMethod(failure.getTestIdentifier().getUniqueId()));
            Throwable exception = failure.getException();
            if (exception != null) {
                logger.warn("Failure message: ", exception);
            }
        });
        if (summary.getTestsFailedCount() > 0) {
            logger.warn("All Failed tests:");
            for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                TestIdentifier failedTest = failure.getTestIdentifier();
                logger.warn(failedTest.getDisplayName() + ": " + failedTest.getUniqueId());
            }
        }
    }

    /**
     * Convert the JUnit-generated unique test ID to standard "class+method" format
     *
     * @param input The unique test ID string
     * @return Test method name in the standard format (i.e. com.example.exampleTest#TestSomething)
     */
    private static String extractTestMethod(String input) {
        // Find the index of the first occurrence of "[test:"
        int startIndex = input.indexOf("[test:");
        if (startIndex == -1) {
            // if not found, then no runner included, use an alternative parsing logic
            return extractTestMethodAlternative(input);
        }

        // Find the index of the last occurrence of "]"
        int endIndex = input.lastIndexOf("]");
        if (endIndex == -1 || endIndex <= startIndex) {
            return ""; // "]" not found or occurs before "[test:"
        }

        // Extract the substring between parentheses
        String testInfo = input.substring(startIndex + "[test:".length(), endIndex);
        
        // Extract the substring enclosed in parentheses
        int classNameStartIndex = testInfo.indexOf("(");
        if (classNameStartIndex == -1) {
            return "";
        }
        int classNameEndIndex = testInfo.lastIndexOf(")");
        if (classNameEndIndex == -1 || classNameEndIndex <= classNameStartIndex) {
            return ""; 
        }
        String className = testInfo.substring(classNameStartIndex + 1, classNameEndIndex);

        // Method name is before opening parenthesis
        String methodName = testInfo.substring(0, classNameStartIndex);

        // Concatenate class name, method name, and #
        return className + "#" + methodName;
    }

    /**
     * Convert test ID to "class+method" format when runner not included
     *
     * @param input The unique test ID string
     * @return Test method name in the standard format (i.e. com.example.exampleTest#TestSomething)
     */
    public static String extractTestMethodAlternative(String input) {
        String[] parts = input.split("/");

        String className = null;
        String methodName = null;

        // Extract class and method names
        for (String part : parts) {
            if (part.startsWith("[class:")) {
                className = part.split("\\[class:")[1].split("]")[0];
            } else if (part.startsWith("[method:")) {
                methodName = part.split("\\[method:")[1].split("]")[0];
            }
        }

        // Concatenate class and method names with "#" separator
        if (className != null && methodName != null) {
            String concatenated = className + "#" + methodName;
            return concatenated.split("\\(")[0];
        } else {
            return null;
        }
    }

    /**
     * Ensures that the specified object is loaded by IsolatedURLClassLoader.
     *
     * @param o the object to check
     * @throws MojoExecutionException if the object is not loaded by IsolatedURLClassLoader
     */
    private static void ensureLoadedInIsolatedClassLoader(Object o) throws MojoExecutionException {
        String objectClassLoader = o.getClass().getClassLoader().getClass().getName();

        // Can't do instanceof here because they are not instances of each other.
        if (!objectClassLoader.equals(IsolatedURLClassLoader.class.getName())) {
            throw new MojoExecutionException(String.format(
                    "Instance of %s not loaded by an IsolatedURLClassLoader (loaded by %s)",
                    o.getClass(), objectClassLoader));
        }
    }
}
