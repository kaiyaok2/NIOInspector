package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog; 
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
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
 * Utility class for running tests in an isolated class loader.
 */
public class ClassLoaderIsolatedTestRunner {

    private static final Logger logger = LoggerFactory.getLogger(ClassLoaderIsolatedTestRunner.class);

    /**
     * Constructs a ClassLoaderIsolatedTestRunner.
     *
     * @throws MojoExecutionException if an error occurs during construction
     */
    public ClassLoaderIsolatedTestRunner() throws MojoExecutionException {
        // Disallow construction at all from wrong ClassLoader
        ensureLoadedInIsolatedClassLoader(this);
    }

    /**
     * Runs the tests reflectively using the provided class loader.
     *
     * @param testClasses the list of test classes to run
     * @param classLoader the class loader to use for loading test classes
     * @param numReruns the number of times to rerun the tests
     * @throws MojoExecutionException if an error occurs during test execution
     */
    public void run_invokedReflectively(List<String> testClasses, ClassLoader classLoader, int numReruns) throws MojoExecutionException {
        // Make sure we are not accidentally working in the system CL
        ensureLoadedInIsolatedClassLoader(this);

        // Load classes
        List<Class<?>> classesList = new ArrayList<>();
        for (int i = 0; i < testClasses.size(); i++) {
            String test = testClasses.get(i);
            try {
                Class<?> currentClass = Class.forName(test, true, classLoader);
                classesList.add(currentClass);
            } catch (Exception | Error e) {
                String msg = "The [" + test + "] test class is not found.";
                logger.warn(msg);
                logger.warn(e.toString());
                continue;
            }
        }
        Class<?>[] classes = classesList.toArray(new Class<?>[0]);

        // Run both JUnit 4 and 5 tests using Vintage Engine
        runJUnitTests(classes, classLoader, numReruns);
    }

    /**
     * Runs JUnit 4/5 tests.
     *
     * @param classes the array of test classes
     * @param classLoader the class loader to use for running the tests
     * @param numReruns the number of times to rerun the tests
     * @throws MojoExecutionException if an error occurs during test execution
     */
    private void runJUnitTests(Class<?>[] classes, ClassLoader classLoader, int numReruns) throws MojoExecutionException {
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

        LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> testClass : classes) {
            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
        }

        // First Run
        logger.info("");
        logger.info("====================Starting the Initial Run of Test====================");
        logger.info("");
        launcher.execute(requestBuilder.build());
        final Map<String, Boolean> testStatusInFirstRun = new HashMap<>(listener.getTestPassStatus());
        TestExecutionSummary summary = listener.getSummary();
        printSummary(summary);

        // Reruns
        Map<String, Integer> NIOtests = new HashMap<>();
        for (int i = 0; i < numReruns; i++) {
            logger.info("");
            logger.info("=======================Starting Rerun #" + (i + 1) + "=========================");
            logger.info("");
            final int iteration = i;
            launcher.execute(requestBuilder.build());
            summary = listener.getSummary();
            summary.getFailures().forEach(failure -> {
                TestIdentifier testIdentifier = failure.getTestIdentifier();
                String testUniqueId = testIdentifier.getUniqueId();
                String testString = extractTestMethod(testUniqueId);
                if (testStatusInFirstRun.containsKey(testUniqueId) && 
                    testStatusInFirstRun.get(testUniqueId)) {
                        // Test passed in the first iteration but failed in later iteration
                        if (NIOtests.containsKey(testString)) {
                            NIOtests.put(testString, NIOtests.get(testString) + 1);
                        } else {
                            NIOtests.put(testString, 1);
                        }
                }
            });
            printSummary(summary);
        }

        // Log final results
        logger.info("");
        logger.info("=========================Final Results=========================");
        logger.info("");
        if (NIOtests.isEmpty()) {
            logger.info("No NIO Tests Found");
        } else {
            for (Map.Entry<String, Integer> entry : NIOtests.entrySet()) {
                logger.error(entry.getKey() + " (passed in the initial run but failed in " +
                    entry.getValue() + " out of " + numReruns + " reruns)");
            }
        }
        
    }

    private void printSummary(TestExecutionSummary summary) {
        summary.printTo(new PrintWriter(System.out));
        summary.getFailures().forEach(failure -> {
            logger.warn("Failure in container: " + failure.getTestIdentifier().getDisplayName());
            Throwable exception = failure.getException();
            if (exception != null) {
                logger.warn("Failure message: " + exception.getMessage());
                exception.printStackTrace(System.out);
            }
        });
        if (summary.getTestsFailedCount() > 0) {
            logger.warn("Failed tests:");
            for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                TestIdentifier failedTest = failure.getTestIdentifier();
                System.out.println(failedTest.getDisplayName() + ": " + failedTest.getUniqueId());
            }
        }
    }

    private static String extractTestMethod(String input) {
        // Find the index of the first occurrence of "[test:"
        int startIndex = input.indexOf("[test:");
        if (startIndex == -1) {
            return ""; // "[test:" not found in the input
        }
        
        // Find the index of the last occurrence of "]"
        int endIndex = input.lastIndexOf("]");
        if (endIndex == -1 || endIndex <= startIndex) {
            return ""; // "]" not found or occurs before "[test:"
        }

        // Extract the substring between startIndex and endIndex
        String testInfo = input.substring(startIndex + "[test:".length(), endIndex);
        
        int classNameStartIndex = testInfo.indexOf("(");
        if (classNameStartIndex == -1) {
            return ""; // Opening parenthesis not found
        }

        int classNameEndIndex = testInfo.lastIndexOf(")");
        if (classNameEndIndex == -1 || classNameEndIndex <= classNameStartIndex) {
            return ""; // Closing parenthesis not found or occurs before opening parenthesis
        }

        // Extract the substring enclosed in parentheses
        String className = testInfo.substring(classNameStartIndex + 1, classNameEndIndex);

        // Method name is before opening parenthesis
        String methodName = testInfo.substring(0, classNameStartIndex);

        // Concatenate class name, method name, and #
        return className + "#" + methodName;
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


