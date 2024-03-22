package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import java.lang.reflect.Method;
import java.lang.Thread;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public class ClassLoaderIsolatedTestRunner {

    public ClassLoaderIsolatedTestRunner() throws MojoExecutionException {
        // Disallow construction at all from wrong ClassLoader
        ensureLoadedInIsolatedClassLoader(this);
    }

    public void run_invokedReflectively(List<String> testClasses, ClassLoader classLoader, int numReruns) throws MojoExecutionException {
        // Make sure we are not accidentally working in the system CL
        ensureLoadedInIsolatedClassLoader(this);

        // Load classes
        Class<?>[] classes = new Class<?>[testClasses.size()];
        for (int i = 0; i < testClasses.size(); i++) {
            String test = testClasses.get(i);
            try {
                classes[i] = Class.forName(test, true, classLoader);
            } catch (ClassNotFoundException e) {
                String msg = "Unable to find class file for test [" + test + "]. Make sure all " +
                        "tests sources are either included in this test target via a 'src' " +
                        "declaration.";
                throw new MojoExecutionException(msg, e);
            }
        }

        // Run JUnit 4 tests
        runJUnit4Tests(classes, classLoader, numReruns);

        // Run JUnit 5 tests
        runJUnit5Tests(classes, classLoader, numReruns);
    }

    private void runJUnit4Tests(Class<?>[] classes, ClassLoader classLoader, int numReruns) throws MojoExecutionException {
        JUnitCore junit = new JUnitCore();
        ensureLoadedInIsolatedClassLoader(junit);
        for (int i = 0; i < numReruns; i++) {
            Result result = junit.run(classes);
            // Print test results
            System.out.println("JUnit 4: Number of tests executed: " + result.getRunCount());
            System.out.println("JUnit 4: Number of tests failed: " + result.getFailureCount());

            // Print details of failed tests
            if (!result.wasSuccessful()) {
                System.out.println("Failed tests:");
                for (Failure failure : result.getFailures()) {
                    System.out.println(failure.toString());
                }
            }
        }
    }

    private void runJUnit5Tests(Class<?>[] classes, ClassLoader classLoader, int numReruns) throws MojoExecutionException {
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

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        for (int i = 0; i < numReruns; i++) {
            launcher.registerTestExecutionListeners(listener);
            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
            for (Class<?> testClass : classes) {
                requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
            }
            launcher.execute(requestBuilder.build());
            TestExecutionSummary summary = listener.getSummary();
            
            // Print test results
            System.out.println("JUnit 5: Number of tests successful: " + summary.getTestsSucceededCount());
            System.out.println("JUnit 5: Number of tests failed: " + summary.getTestsFailedCount());
            
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
