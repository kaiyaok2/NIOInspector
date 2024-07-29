package edu.illinois.NIOInspector.plugin.mojo;

import static edu.illinois.NIOInspector.plugin.util.extractors.StackTraceLineNumberExtractor.findLineNumberInStackTrace;
import static edu.illinois.NIOInspector.plugin.util.extractors.MostRecentLogFinder.findMostRecentLog;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mojo to collect three pieces of information from a previous rerun:
 * 1. A list of possible NIO tests.
 * 2. The stacktrace of the first rerun of each possible NIO test.
 * 3. The reduced method source code of each possible NIO test.
 */
@Mojo(name = "collectTestInfo", defaultPhase = LifecyclePhase.INITIALIZE)
public class CollectTestInfoMojo extends AbstractMojo {

    /**
     * Log file produced by running the Rerun Mojo
     */
    @Parameter(property = "logFile")
    private String logFilePath;
    
    /**
     * Directory to test source files. (i.e. src/test/...)
     */
    @Parameter(property = "testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    /**
     * Executes the Mojo to collect test information.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {
        File logFile = null;
        // Log file not provided; default to file produced by most recent run
        if (logFilePath == null) {
            getLog().warn("Log not provided; uses most recent log.");
            logFile = findMostRecentLog();
        } else {
            logFile = new File(logFilePath);
        }

        String parentDirectory = logFile.getParent();

        // Get a list of all possible NIO tests
        List<String> possibleNIOTests = getPossibleNIOTests(logFile);
        if (possibleNIOTests.isEmpty()) {
            getLog().info("No error strings found");
            return;
        }

        // Write the list of possible NIO tests
        try (FileWriter writer = new FileWriter(new File(parentDirectory, "possible-NIO-list.txt"))) {
            for (String line : possibleNIOTests) {
                writer.write(line + System.lineSeparator());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (String possibleNIOTest : possibleNIOTests) {
            // Write reduced test code at method granularity
            writeReducedTestFile(possibleNIOTest, parentDirectory);

            // Write stacktrace of the failure in each rerun
            writeStackTrace(possibleNIOTest, parentDirectory, logFile);
        }
    }

    /**
     * Write the stack trace of a possible NIO test in each rerun
     * @param possibleNIOTest The name of the test to write stack trace for
     * @param parentDirectory The directory to store the stack trace written
     * @param logFile The log file produced by running the Rerun Mojo
     */
    public void writeStackTrace(String possibleNIOTest, String parentDirectory, File logFile) throws MojoExecutionException {
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int startLine = -1;
            boolean inRerunExamination = false;
            int lineNum = 0;
            int rerunNum = 0;
            Pattern rerunStartPattern = Pattern.compile("\\[INFO\\] =======================Starting Rerun #");
            Pattern failingTestReportPattern = Pattern.compile("\\[WARN\\] Failing Test: " + Pattern.quote(possibleNIOTest));
            Pattern failureMessagePattern = Pattern.compile("Failure message:");
            String NIOTestName = possibleNIOTest.replace("#", ".");
            // Find the stacktrace of the possible NIOTest in each rerun.
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (!inRerunExamination) {
                    Matcher rerunStartMatcher = rerunStartPattern.matcher(line);
                    if (rerunStartMatcher.find()) {
                        inRerunExamination = true;
                        rerunNum++;
                    }
                } else {
                    Matcher failingTestReportMatcher = failingTestReportPattern.matcher(line);
                    if (failingTestReportMatcher.find()) {
                        startLine = lineNum + 1;
                    }
                }
                Matcher failureMatcher = failureMessagePattern.matcher(line);
                if (failureMatcher.find() && startLine != -1) {
                    extractStackTrace(startLine, parentDirectory, logFile, NIOTestName, rerunNum);
                    inRerunExamination = false;
                    startLine = -1;
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("An error occurred while processing log file.", e);
        }
    }

    /**
     * Helper of `writeStackTrace()` to extract stacktrace chunk from the log, given start line
     * @param startLine Line number of the start line of the stack trace chunk
     * @param parentDirectory The directory to store the stack trace written
     * @param logFile The log file produced by running the Rerun Mojo
     * @param NIOTestName The name of the NIO method to be used as part of file name of the written stack trace
     * @param rerunNum The rerun number to examine
     */
    private void extractStackTrace(int startLine, String parentDirectory, File logFile, String NIOTestName, int rerunNum) throws IOException {
        File subDirectory = new File(parentDirectory + File.separator + NIOTestName);
        if (!subDirectory.exists()) {
            subDirectory.mkdir();
        }
        File stackTraceOfRerunNum = new File(subDirectory, "stacktrace" + rerunNum);
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
            BufferedWriter writer = new BufferedWriter(new FileWriter(stackTraceOfRerunNum))) {
            String line;
            int lineNum = 0;
            boolean startedWriting = false;
            // The stack trace chunk does not start with a level header (i.e. "[INFO]"")
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum >= startLine) {
                    if (!line.startsWith("[WARN]") && !line.startsWith("[INFO]") && !line.startsWith("[ERROR]")) {
                        startedWriting = true;
                        writer.write(line);
                        writer.newLine();
                    } else if (startedWriting) {
                        break;
                    }
                }
            }
            getLog().info("Extracted log for rerun #" + rerunNum + " saved to " + subDirectory.getName());
        }
        File bugLineOfRerunNum = new File(subDirectory, "error_line" + rerunNum);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(bugLineOfRerunNum))) {
            String testClassPath = NIOTestName.substring(0, NIOTestName.lastIndexOf('.'));
            int bugLineNum = findLineNumberInStackTrace(stackTraceOfRerunNum, testClassPath);
            File testFile = new File(testSourceDirectory, testClassPath.replace('.', File.separatorChar) + ".java");
            if (!testFile.exists()) {
                getLog().warn("Test file not found: " + testFile.getAbsolutePath());
                return;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(testFile))) {
                int curLineNum = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    curLineNum ++;
                    if (curLineNum == bugLineNum) {
                        writer.write(line);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Parses the rerun log and extracts string representation of possible NIO methods
     * @param logFile The log file to parse.
     * @return A list of error strings.
     */
    private List<String> getPossibleNIOTests(File logFile) {
        List<String> possibleNIOTests = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            boolean startProcessing = false;

            // Find the line number of the "Final Results" line
            while ((line = reader.readLine()) != null) {
                if (line.contains("[INFO] =========================Final Results=========================")) {
                    startProcessing = true;
                    break;
                }
            }

            if (startProcessing) {
                // Move the reader to the target line: second line after the start line
                for (int i = 0; i < 2; i++) {
                    reader.readLine();
                }

                // Read the content of the target line
                line = reader.readLine();
                while (line != null && line.startsWith("[ERROR] ")) {
                    // Extract the error string
                    String possibleNIOTest = line.substring("[ERROR] ".length()).split(" \\(passed in the initial run")[0];
                    possibleNIOTests.add(possibleNIOTest);
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            getLog().error("Error reading log file: " + logFile.getAbsolutePath(), e);
        }
        return possibleNIOTests;
    }

    /**
     * Implementation of a test method may be in a parent class - finds it if necessary
     * @param parentClass The name of the parent class.
     * @param directory The directory to search in.
     * @return File object w.r.t the parent test class or null if not found.
     */
    private File findParentTestClassFile(String parentClass, File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null; // Invalid directory
        }

        // Search for the file recursively
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                // Recursively search in subdirectories
                File result = findParentTestClassFile(parentClass, file);
                if (result != null) {
                    return result; // Found in a subdirectory
                }
            } else if (file.getName().equals(parentClass + ".java")) {
                return file; // Found the matching file
            }
        }

        return null; // Parent class not found in fs
    }

    /**
     * Writes the reduced test file w.r.t one possible NIO test
     * @param possibleNIOTest The string containing class and method names.
     * @param parentDirectory The parent directory of the files to be written.
     */
    private void writeReducedTestFile(String possibleNIOTest, String parentDirectory) {
        String[] errorParts = possibleNIOTest.split("#");
        if (errorParts.length != 2) {
            getLog().warn("Invalid error string format: " + possibleNIOTest);
            return;
        }

        String classPath = errorParts[0];
        String methodName = errorParts[1];

        File testFile = new File(testSourceDirectory, classPath.replace('.', File.separatorChar) + ".java");
        if (!testFile.exists()) {
            getLog().warn("Test file not found: " + testFile.getAbsolutePath());
            return;
        }
        writeBuggyJavaFile(classPath, methodName, testFile, parentDirectory);
    }

    /**
     * Helper of `writeReducedTestFile()` - reduce test code and write to output
     * @param classPath The path to the test class.
     * @param methodName The name of the test method.
     * @param testFile The test file containing the source code of the test method.
     * @param parentDirectory The parent directory of the files to be written.
     */
    private void writeBuggyJavaFile(String classPath, String methodName, File testFile, String parentDirectory) {
        try (BufferedReader reader = new BufferedReader(new FileReader(testFile))) {
            StringBuilder testContent = new StringBuilder();
            StringBuilder allAnnotations = new StringBuilder();
            String line;
            boolean methodStarted = false;
            int braceCounter = 0;
            boolean hasParentClass = false;
            boolean testFound = false;
            String parentClass = null;
            Pattern inheritancePattern = Pattern.compile("public class\\s+(\\w+)\\s+extends\\s+(\\w+)");
            boolean packageDefined = false;
            boolean inBlockComment = false;

            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("package ")) {
                    packageDefined = true;
                }
                Matcher matcher = inheritancePattern.matcher(line);
                // Check if class inheritance is found
                if (matcher.find()) {
                    // Extract the parent test case name
                    parentClass = matcher.group(2);
                    hasParentClass = true;
                }
                if (!methodStarted && (line.trim().startsWith("@Test") || line.trim().startsWith("@org.junit.Test"))) {
                    // Start of a test method
                    methodStarted = true;
                    allAnnotations.append(line).append(System.lineSeparator());
                } else if (methodStarted && line.trim().startsWith("@")) {
                    // Other annotations (e.g. @Override)
                    allAnnotations.append(line).append(System.lineSeparator());
                } else if (inBlockComment && line.trim().contains("*/")) {
                    inBlockComment = false;
                    continue;
                } else if (line.trim().startsWith("//") || inBlockComment) {
                    // skip lines of comments
                    continue;
                } else if (line.trim().startsWith("/*")) {
                    inBlockComment = true;
                    if (line.trim().contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                } else if (methodStarted && line.contains(methodName + "(")) {
                    testFound = true;
                    testContent.append(allAnnotations.toString());
                    // Found the start of the specified test method
                    testContent.append(line).append(System.lineSeparator());
                    for (char c : line.toCharArray()) {
                        if (c == '{') {
                            braceCounter++;
                        } else if (c == '}') {
                            braceCounter--;
                            if (braceCounter == 0) {
                                methodStarted = false;
                                break;
                            }
                        }
                    }
                    if (!methodStarted) {
                        continue;
                    }
                    // Read until the end of the method
                    while ((line = reader.readLine()) != null) {
                        testContent.append(line).append(System.lineSeparator());
                        for (char c : line.toCharArray()) {
                            if (c == '{') {
                                braceCounter++;
                            } else if (c == '}') {
                                braceCounter--;
                                if (braceCounter == 0) {
                                    methodStarted = false;
                                    break;
                                }
                            }
                        }
                        if (!methodStarted) {
                            break;
                        }
                    }
                } else if (methodStarted) {
                    // Skip all other test methods
                    for (char c : line.toCharArray()) {
                        if (c == '{') {
                            braceCounter++;
                        } else if (c == '}') {
                            braceCounter--;
                            if (braceCounter == 0) {
                                methodStarted = false;
                                break;
                            }
                        }
                    }
                    if (!methodStarted) {
                        continue;
                    }
                    while ((line = reader.readLine()) != null) {
                        for (char c : line.toCharArray()) {
                            if (c == '{') {
                                braceCounter++;
                            } else if (c == '}') {
                                braceCounter--;
                                if (braceCounter == 0) {
                                    methodStarted = false;
                                    break;
                                }
                            }
                        }
                        if (!methodStarted) {
                            break;
                        }
                    }
                } else {
                    if (packageDefined) {
                        testContent.append(line).append(System.lineSeparator());
                    }
                }
            }

            // Check if implementation available in parent classes
            if (!testFound) {
                if (hasParentClass) {
                    File parentClassFile = findParentTestClassFile(parentClass, testSourceDirectory);
                    writeBuggyJavaFile(classPath, methodName, parentClassFile, parentDirectory);
                    return;
                }
                getLog().warn("Test method not found: " + methodName + " in " + classPath);
                return;
            }

            // Write reduced test source code
            File subDirectory = new File(parentDirectory + File.separator + classPath + "." + methodName);
            if (!subDirectory.exists()) {
                subDirectory.mkdir();
            }
            try (FileWriter writer = new FileWriter(new File(subDirectory, "buggyTestMethod"))) {
                writer.write(testContent.toString().replaceAll("(?m)^\\s*$[\r\n]*", ""));
                getLog().info("Reduced test source code written to: " + subDirectory);
            } catch (IOException e) {
                getLog().error("Error writing reduced test source code: ", e);
            }
        } catch (IOException e) {
            getLog().error("Error reading file: " + testFile.getAbsolutePath(), e);
        }
    }
}
