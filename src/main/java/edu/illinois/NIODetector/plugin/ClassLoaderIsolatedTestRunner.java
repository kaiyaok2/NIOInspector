package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import java.util.List;

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

        // Run
        JUnitCore junit = new JUnitCore();
        ensureLoadedInIsolatedClassLoader(junit);
        for (int i = 0; i < numReruns; i++) {
            Result result = junit.run(classes);
            // Print test results
            System.out.println("Number of tests executed: " + result.getRunCount());
            System.out.println("Number of tests failed: " + result.getFailureCount());

            // Print details of failed tests
            if (!result.wasSuccessful()) {
                System.out.println("Failed tests:");
                for (Failure failure : result.getFailures()) {
                    System.out.println(failure.toString());
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

