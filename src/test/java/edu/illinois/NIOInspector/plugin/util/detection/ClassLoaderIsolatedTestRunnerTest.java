package edu.illinois.NIOInspector.plugin.util.detection;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;


class ClassLoaderIsolatedTestRunnerTest {

    @Test
    void testSanityCheck() throws MojoExecutionException {
        try {
            new ClassLoaderIsolatedTestRunner();
            fail("Mojo Execution shall be thrown since the current instance is not loaded by IsolatedURLClassLoader");
        } catch (MojoExecutionException e) {}
    }

    @Test
    void testExtractTestMethod() throws Exception {
        String uniqueId = "[engine:junit-vintage]/[runner:com.example.ExampleTest]/[test:testMethod(com.example.ExampleTest)]";
        
        Method extractTestMethodMethod = ClassLoaderIsolatedTestRunner.class.getDeclaredMethod("extractTestMethod", String.class);
        extractTestMethodMethod.setAccessible(true);

        String result = (String) extractTestMethodMethod.invoke(null, uniqueId);

        assertEquals(result, "com.example.ExampleTest#testMethod");
        extractTestMethodMethod.setAccessible(false);
    }

    @Test
    void testExtractTestMethodAlternative() {
        String uniqueId = "[engine:junit-jupiter]/[class:com.example.ExampleTest]/[method:testMethod()]";

        String result = ClassLoaderIsolatedTestRunner.extractTestMethodAlternative(uniqueId);

        assertEquals(result, "com.example.ExampleTest#testMethod");
    }
}

