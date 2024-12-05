package edu.illinois.NIOInspector.plugin.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

public class CollectTestInfoMojoTest {

    private CollectTestInfoMojo mojo;
    private Log mockLog;
    private File tempDir;
    private File logFile;

    @BeforeEach
    public void setUp() throws Exception {
        mojo = new CollectTestInfoMojo();
        setPrivateField(mojo, "logFilePath", null);
        setPrivateField(mojo, "testSourceDirectory", new File("src/test/java"));
        mockLog = mock(Log.class);
        mojo.setLog(mockLog);

        tempDir = Files.createTempDirectory("testDir").toFile();
        tempDir.mkdirs();  // Ensure the directory is created

        logFile = new File(tempDir, "rerun-results.log");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("[INFO] \n");
            writer.write("[INFO] ====================Starting the Initial Run of Test====================\n");
            writer.write("[INFO] \n");
            writer.write("[INFO] \n");
            writer.write("[INFO] =======================Starting Rerun #1=========================\n");
            writer.write("[INFO] \n");
            writer.write("[WARN] Failing Test: anonymized.path.plugin.mojo.CollectTestInfoMojoTest#testMethod\n");
            writer.write("[WARN] Failure message: \n");
            writer.write("java.lang.AssertionError: expected:<true> but was:<false>\n");
            writer.write("\tat anonymized.path.plugin.mojo.CollectTestInfoMojoTest.testMethod(CollectTestInfoMojoTest.java:10)\n");
            writer.write("[WARN] All Failed tests:\n");
            writer.write("[WARN] testMethod(): [engine:junit-jupiter]/[class:anonymized.path.plugin.mojo.CollectTestInfoMojoTest]/[method:testMethod()]\n");
            writer.write("[INFO] \n");
            writer.write("[INFO] =========================Final Results=========================\n");
            writer.write("[INFO] \n");
            writer.write("[ERROR] Number of Possible NIO Test(s) Found: 1\n");
            writer.write("[ERROR] anonymized.path.plugin.mojo.CollectTestInfoMojoTest#testMethod (passed in the initial run but failed in 1 out of 1 reruns)\n");
        }
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Delete the temporary directory and its contents
        if (tempDir != null && tempDir.exists()) {
            Files.walk(tempDir.toPath())
                    .sorted((p1, p2) -> p2.compareTo(p1))  // Sort in reverse order to delete files before directories
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        field.setAccessible(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetPossibleNIOTests() throws Exception {
        Method method = CollectTestInfoMojo.class.getDeclaredMethod("getPossibleNIOTests", File.class);
        method.setAccessible(true);

        List<String> result = (List<String>) method.invoke(mojo, logFile);
        method.setAccessible(false);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("anonymized.path.plugin.mojo.CollectTestInfoMojoTest#testMethod", result.get(0));
    }

    @Test
    public void testWriteStackTrace() throws Exception {
        Method method = CollectTestInfoMojo.class.getDeclaredMethod("writeStackTrace", String.class, String.class, File.class);
        method.setAccessible(true);

        method.invoke(mojo, "anonymized.path.plugin.mojo.CollectTestInfoMojoTest#testMethod", tempDir.getAbsolutePath(), logFile);
        method.setAccessible(false);

        File stackTraceFile = new File(tempDir, "anonymized.path.plugin.mojo.CollectTestInfoMojoTest.testMethod/stacktrace1");
        assertTrue(stackTraceFile.exists());
        verify(mockLog).info(anyString());
    }

    @Test
    public void testExtractStackTrace() throws Exception {
        Method method = CollectTestInfoMojo.class.getDeclaredMethod("extractStackTrace", int.class, String.class, File.class, String.class, int.class);
        method.setAccessible(true);

        method.invoke(mojo, 3, tempDir.getAbsolutePath(), logFile, "anonymized.path.plugin.mojo.CollectTestInfoMojoTest", 1);
        method.setAccessible(false);

        File stackTraceFile = new File(tempDir, "anonymized.path.plugin.mojo.CollectTestInfoMojoTest/stacktrace1");
        assertTrue(stackTraceFile.exists());
    }

    @Test
    public void testWriteReducedTestFile() throws Exception {
        Method method = CollectTestInfoMojo.class.getDeclaredMethod("writeReducedTestFile", String.class, String.class);
        method.setAccessible(true);

        method.invoke(mojo, "anonymized.path.plugin.mojo.CollectTestInfoMojoTest#testMethod", tempDir.getAbsolutePath());
        method.setAccessible(false);

        verify(mockLog).warn(anyString());
    }

    @Test
    public void testExecute() throws Exception {
        setPrivateField(mojo, "logFilePath", logFile.getAbsolutePath());
        mojo.execute();
        verify(mockLog).info(anyString());
    }
}
