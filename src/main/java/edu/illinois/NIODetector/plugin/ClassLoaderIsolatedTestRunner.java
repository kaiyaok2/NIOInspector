package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.TestIdentifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for running tests in an isolated class loader.
 */
public class ClassLoaderIsolatedTestRunner {

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
                System.out.println(msg);
                System.out.println(e);
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

        LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> testClass : classes) {
            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
        }
        for (int i = 0; i < numReruns; i++) {
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(requestBuilder.build());
            TestExecutionSummary summary = listener.getSummary();
            
            summary.printTo(new PrintWriter(System.out));

            // Retrieve and print failure messages for failed containers
            summary.getFailures().forEach(failure -> {
                System.out.println("Failure in container: " + failure.getTestIdentifier().getDisplayName());
                Throwable exception = failure.getException();
                if (exception != null) {
                    System.out.println("Failure message: " + exception.getMessage());
                    exception.printStackTrace(System.out);
                }
            });
            
            // Print details of failed tests
            if (summary.getTestsFailedCount() > 0) {
                System.out.println("Failed tests:");
                for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                    TestIdentifier failedTest = failure.getTestIdentifier();
                    System.out.println(failedTest.getDisplayName() + ": " + failedTest.getUniqueId());
                }
            }
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


