package edu.illinois.NIOInspector.plugin.util.extractors;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.junit.jupiter.api.Test;

public class StackTraceLineNumberExtractorTest {

    private static final String CLASS_NAME = "com.mycompany.app.AppTest";
    private static final String LOG_FILE_CONTENT =
        " at com.mycompany.app.AppTest.testMethod(AppTest.java:42)\n" +
        " at com.mycompany.app.AnotherClass.anotherMethod(AnotherClass.java:24)\n";

    @Test
    public void testFindLineNumberInStackTraceSuccess() throws IOException {
        File logFile = createTempLogFile(LOG_FILE_CONTENT);

        int result = StackTraceLineNumberExtractor.findLineNumberInStackTrace(logFile, CLASS_NAME);

        assertEquals(42, result, "Expected line number to be 42");
        logFile.delete();
    }

    @Test
    public void testFindLineNumberInStackTraceClassNotFound() throws IOException {
        String content = "at com.mycompany.app.OtherClass.someMethod(OtherClass.java:56)\n";
        File logFile = createTempLogFile(content);

        int result = StackTraceLineNumberExtractor.findLineNumberInStackTrace(logFile, "com.nonexistent.Class");

        assertEquals(-1, result, "Expected line number to be -1 when class is not found");
        logFile.delete();
    }

    @Test
    public void testFindLineNumberInStackTraceEmptyLogFile() throws IOException {
        File logFile = createTempLogFile("");

        int result = StackTraceLineNumberExtractor.findLineNumberInStackTrace(logFile, CLASS_NAME);

        assertEquals(-1, result, "Expected line number to be -1 for an empty log file");
        logFile.delete();
    }

    // Helper method to create a temporary log file with specified content
    private File createTempLogFile(String content) throws IOException {
        File tempFile = File.createTempFile("test-log-", ".log");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(content);
        }
        return tempFile;
    }
}
