package edu.illinois.NIOInspector.plugin.mojo;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CleanMojoTest {

    private CleanMojo cleanMojo;
    private Log log;

    @BeforeEach
    void setUp() {
        cleanMojo = new CleanMojo();
        log = mock(Log.class);
        cleanMojo.setLog(log);
    }

    @Test
    void testExecuteDeletesNIOInspectorDirectory() throws IOException {
        // Setup temporary directory
        Path tempDir = Files.createTempDirectory("test-project");
        File nioInspectorDir = new File(tempDir.toFile(), ".NIOInspector");
        nioInspectorDir.mkdir();

        // Verify directory exists
        assertTrue(nioInspectorDir.exists());

        // Set project base directory and execute mojo
        cleanMojo.setProjectBaseDirectory(tempDir.toFile());
        cleanMojo.execute();

        // Verify directory is deleted
        assertFalse(nioInspectorDir.exists());
        verify(log).info("Deleted .NIOInspector directory.");
    }

    @Test
    void testExecuteNoNIOInspectorDirectory() throws IOException {
        // Setup temporary directory
        Path tempDir = Files.createTempDirectory("test-project");
        File nioInspectorDir = new File(tempDir.toFile(), ".NIOInspector");

        // Verify directory does not exist
        assertFalse(nioInspectorDir.exists());

        // Set project base directory and execute mojo
        cleanMojo.setProjectBaseDirectory(tempDir.toFile());
        cleanMojo.execute();

        // Verify log message
        verify(log).info(".NIOInspector directory does not exist.");
    }

    @Test
    void testDeleteDirectoryWithReflection() throws Exception {
        // Setup temporary directory
        Path tempDir = Files.createTempDirectory("test-delete-directory");
        File nioInspectorDir = new File(tempDir.toFile(), ".NIOInspector");
        nioInspectorDir.mkdir();
        File testFile = new File(nioInspectorDir, "test.txt");
        testFile.createNewFile();

        // Verify directory and file exist
        assertTrue(nioInspectorDir.exists());
        assertTrue(testFile.exists());

        // Access the private deleteDirectory method using reflection
        Method deleteDirectoryMethod = CleanMojo.class.getDeclaredMethod("deleteDirectory", File.class);
        deleteDirectoryMethod.setAccessible(true);

        // Invoke the deleteDirectory method
        deleteDirectoryMethod.invoke(cleanMojo, nioInspectorDir);

        // Verify directory and file are deleted
        assertFalse(nioInspectorDir.exists());
        assertFalse(testFile.exists());

        deleteDirectoryMethod.setAccessible(false);
    }
}
