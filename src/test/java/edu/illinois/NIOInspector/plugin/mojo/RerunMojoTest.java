package edu.illinois.NIOInspector.plugin.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

public class RerunMojoTest {

    private RerunMojo mojo;
    @Mock
    private MavenProject mockProject;
    @Mock
    private Log mockLog;

    private File tempDir;
    private File testClassesDir;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        mojo = new RerunMojo();
        setPrivateField(mojo, "project", mockProject);
        setPrivateField(mojo, "pluginArtifacts", new ArrayList<>());
        setPrivateField(mojo, "numReruns", 3);

        // Setup temporary directory for test classes
        tempDir = new File(System.getProperty("java.io.tmpdir"), "testDir");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        testClassesDir = new File(tempDir, "test-classes");
        testClassesDir.mkdirs();

        // Mock MavenProject methods
        Build mockBuild = mock(Build.class);
        when(mockProject.getBuild()).thenReturn(mockBuild);
        when(mockBuild.getTestOutputDirectory()).thenReturn(testClassesDir.getAbsolutePath());
        when(mockProject.getTestClasspathElements()).thenReturn(new ArrayList<>());
        when(mockProject.getRuntimeClasspathElements()).thenReturn(new ArrayList<>());
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Delete the temporary directory and its contents
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        File[] subFiles = file.listFiles();
                        if (subFiles != null) {
                            for (File subFile : subFiles) {
                                subFile.delete();
                            }
                        }
                    }
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        field.setAccessible(false);
    }

    @Test
    public void testExecute() throws Exception {
        // Use reflection to make the private method accessible
        Method executeMethod = RerunMojo.class.getDeclaredMethod("execute");
        executeMethod.setAccessible(true);

        // Invoke `execute()`; everything shall be mockable until running actual tests
        try {
            executeMethod.invoke(mojo);
        } catch (InvocationTargetException e) {
            // Retrieve the actual exception thrown by `execute()`
            Throwable cause = e.getCause();
            assertTrue(cause instanceof MojoExecutionException);
            assertEquals(cause.getMessage(), "Error invoking ClassLoaderIsolatedTestRunner");
        }

    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFindTestClasses() throws Exception {
        // Use reflection to make the private method accessible
        Method findTestClassesMethod = RerunMojo.class.getDeclaredMethod("findTestClasses", String.class);
        findTestClassesMethod.setAccessible(true);

        List<String> testClasses = (List<String>) findTestClassesMethod.invoke(mojo, testClassesDir.getAbsolutePath());

        // Verify the result
        assertNotNull(testClasses);
        assertTrue(testClasses.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testParseSurefireExcludes() throws Exception {
        // Use reflection to access static method
        Method parseSurefireExcludesMethod = RerunMojo.class.getDeclaredMethod("parseSurefireExcludes", MavenProject.class);
        parseSurefireExcludesMethod.setAccessible(true);

        List<String> excludes = (List<String>) parseSurefireExcludesMethod.invoke(null, mockProject);

        // Verify the result
        assertNotNull(excludes);
        assertTrue(excludes.isEmpty());
    }
}
