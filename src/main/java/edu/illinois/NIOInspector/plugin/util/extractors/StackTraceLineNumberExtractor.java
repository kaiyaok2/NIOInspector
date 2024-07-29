package edu.illinois.NIOInspector.plugin.util.extractors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supports extracting the line number of a source file as appearing in error stacktrace
 */
public class StackTraceLineNumberExtractor {

    /**
     * Finds the line number in the stack trace of a log file where a specified class caused an error.
     *
     * This method searches for stack trace entries that match the 
     * specified class name. When a match is found, the method extracts and returns the line number 
     * associated with that class from the stack trace.
     *
     * @param logFile the log file containing the stack trace
     * @param className the fully qualified name of the class to search for in the stack trace
     * @return the line number in the stack trace where the specified class caused an error,
     *         or -1 if the class is not found in the stack trace
     * @throws IOException if an I/O error occurs while reading the log file
     *
     * Example usage:
     * <pre>
     * {@code
     * try {
     *     int lineNumber = findLineNumberInStackTrace("path/to/logfile.log", "com.mycompany.app.AppTest");
     *     if (lineNumber != -1) {
     *         System.out.println("Line number causing the problem in com.mycompany.app.AppTest: " + lineNumber);
     *     } else {
     *         System.out.println("Class com.mycompany.app.AppTest not found in stack trace.");
     *     }
     * } catch (IOException e) {
     *     e.printStackTrace();
     * }
     * }
     * </pre>
     *
     * The stack trace entries are expected to follow the format:
     * <pre>
     * {@code
     *     at com.mycompany.app.xxxTest.testXXX(xxxTest.java:19)
     * }
     * </pre>
     */
    public static int findLineNumberInStackTrace(File logFile, String className) throws IOException {
        Pattern pattern = Pattern.compile("\\s+at\\s+" + Pattern.quote(className) + "\\.(\\w+)\\((\\w+\\.java):(\\d+)\\)");
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(3));
                }
            }
        }
        return -1;
    }
}