package edu.illinois.NIODetector.plugin;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;

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

    private static final String LOG_DIRECTORY = ".NIODetector";

    /**
     * Find source test code w.r.t each possible NIO and reduce it
     */
    public void execute() throws MojoExecutionException {
        File logFile = null;
        // Log file not provided; default to file produced by most recent run
        if (logFilePath == null) {
            getLog().warn("Log not provided; uses most recent log.");

            File logDirectory = new File(LOG_DIRECTORY);

            // List all time-base named subdirectories in the .NIODetector directory
            File[] subdirectories = logDirectory.listFiles(File::isDirectory);

            if (subdirectories != null) {
                // Sort subdirectories by timestamp (descending order)
                Arrays.sort(subdirectories, Comparator.comparingLong(this::getTimestampFromDirectory).reversed());

                // Get the most recent directory
                File mostRecentDirectory = subdirectories[0];

                // Find the rerun-results.log file in the most recent directory
                Optional<File> rerunResultsLogFileOptional = Arrays.stream(mostRecentDirectory.listFiles())
                        .filter(file -> file.getName().equals("rerun-results.log"))
                        .findFirst();

                // Cast Optional<File> to File or throw exception if casting fails
                logFile = rerunResultsLogFileOptional.orElseThrow(() ->
                        new MojoExecutionException("Failed to find a recent rerun-results.log file"));
            }
        } else {
            logFile = new File(logFilePath);
        }

        String parentDirectory = logFile.getParent();

        // Get a list of all possible NIO tests
        List<String> possibleNIOTests = getPossibleNIOTestss(logFile);
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

            // Write stacktrace of the failure in the first rerun
            writeStackTrace(possibleNIOTest, parentDirectory, logFile);
        }
    }

    /**
     * Write the stack trace of a possible NIO test in its first rerun
     * @param possibleNIOTest The name of the test to write stack trace for
     * @param parentDirectory The directory to store the stack trace written
     * @param logFile The log file produced by running the Rerun Mojo
     */
    public void writeStackTrace(String possibleNIOTest, String parentDirectory, File logFile) throws MojoExecutionException {
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int startLine = -1;
            boolean inFirstRerun = false;
            int lineNum = 0;
            Pattern firstRerunStartPattern = Pattern.compile("\\[INFO\\] =======================Starting Rerun #1=========================");
            Pattern failingTestReportPattern = Pattern.compile("\\[WARN\\] Failing Test: " + Pattern.quote(possibleNIOTest));
            Pattern failureMessagePattern = Pattern.compile("Failure message:");
            String NIOTestName = possibleNIOTest.replace("#", ".");
            // Find the stacktrace of the possible NIOTest in the first rerun.
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (!inFirstRerun) {
                    Matcher firstRerunStartMatcher = firstRerunStartPattern.matcher(line);
                    if (firstRerunStartMatcher.find()) {
                        inFirstRerun = true;
                    }
                } else {
                    Matcher failingTestReportMatcher = failingTestReportPattern.matcher(line);
                    if (failingTestReportMatcher.find()) {
                        startLine = lineNum + 1;
                    }
                }
                Matcher failureMatcher = failureMessagePattern.matcher(line);
                if (failureMatcher.find() && startLine != -1) {
                    extractStackTrace(startLine, parentDirectory, logFile, NIOTestName);
                    return;
                }
            }
            getLog().info("Failed to extract log");
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
     */
    private void extractStackTrace(int startLine, String parentDirectory, File logFile, String NIOTestName) throws IOException {
        File subDirectory = new File(parentDirectory + File.separator + NIOTestName);
        if (!subDirectory.exists()) {
            subDirectory.mkdir();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
             BufferedWriter writer = new BufferedWriter(new FileWriter(new File(subDirectory, "stacktrace")))) {
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
            getLog().info("Extracted log saved to extracted_stacktrace_" + logFile.getName());
        }
    }

    /**
     * Get time stamp from the time-based directory name
     * @param directory The directory with time-based name
     * @return A comparable time stamp
     */
    private long getTimestampFromDirectory(File directory) {
        try {
            String timeBasedFileName = directory.getName();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date creationTime = dateFormat.parse(timeBasedFileName);
            return creationTime.getTime();
        } catch (Exception e) {
            getLog().error("Error parsing timestamp for directory: " + directory.getPath(), e);
            return 0;
        }
    }

    /**
     * Parses the rerun log and extracts string representation of possible NIO methods
     * @param logFile The log file to parse.
     * @return A list of error strings.
     */
    private List<String> getPossibleNIOTestss(File logFile) {
        List<String> possibleNIOTestss = new ArrayList<>();

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
                    String possibleNIOTests = line.substring("[ERROR] ".length()).split(" \\(passed in the initial run")[0];
                    possibleNIOTestss.add(possibleNIOTests);
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            getLog().error("Error reading log file: " + logFile.getAbsolutePath(), e);
        }
        return possibleNIOTestss;
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
     * @param possibleNIOTests The string containing class and method names.
     * @param parentDirectory The parent directory of the files to be written.
     */
    private void writeReducedTestFile(String possibleNIOTests, String parentDirectory) {
        String[] errorParts = possibleNIOTests.split("#");
        if (errorParts.length != 2) {
            getLog().warn("Invalid error string format: " + possibleNIOTests);
            return;
        }

        String className = errorParts[0];
        String methodName = errorParts[1];

        File testFile = new File(testSourceDirectory, className.replace('.', File.separatorChar) + ".java");
        if (!testFile.exists()) {
            getLog().warn("Test file not found: " + testFile.getAbsolutePath());
            return;
        }
        writeBuggyJavaFile(className, methodName, testFile, parentDirectory);
    }

    /**
     * Helper of `writeReducedTestFile()` - reduce test code and write to output
     * @param className The name of the test class.
     * @param methodName The name of the test method.
     * @param testFile The test file containing the source code of the test method.
     * @param parentDirectory The parent directory of the files to be written.
     */
    private void writeBuggyJavaFile(String className, String methodName, File testFile, String parentDirectory) {
        try (BufferedReader reader = new BufferedReader(new FileReader(testFile))) {
            StringBuilder testContent = new StringBuilder();
            String line;
            boolean methodStarted = false;
            int braceCounter = 0;
            boolean hasParentClass = false;
            boolean testFound = false;
            String parentClass = null;
            Pattern inheritancePattern = Pattern.compile("public class\\s+(\\w+)\\s+extends\\s+(\\w+)");
            boolean packageDefined = false;
            String cache = null;

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
                if (!methodStarted && line.contains("@Test")) {
                    // Start of a test method
                    methodStarted = true;
                    cache = line;
                } else if (methodStarted && line.trim().startsWith("public void " + methodName)) {
                    testFound = true;
                    testContent.append(cache).append(System.lineSeparator());
                    // Found the start of the specified test method
                    testContent.append(line).append(System.lineSeparator());
                    braceCounter++;
                    // Read until the end of the method
                    while ((line = reader.readLine()) != null) {
                        testContent.append(line).append(System.lineSeparator());
                        for (char c : line.toCharArray()) {
                            if (c == '{') {
                                braceCounter++;
                            } else if (c == '}') {
                                braceCounter--;
                                if (braceCounter == 0) {
                                    break;
                                }
                            }
                        }
                        if (braceCounter == 0) {
                            break;
                        }
                    }
                    break;
                } else {
                    if (!line.trim().startsWith("import") && packageDefined) {
                        testContent.append(line).append(System.lineSeparator());
                    }
                }
            }

            // Check if implementation available in parent classes
            if (!testFound) {
                if (hasParentClass) {
                    File parentClassFile = findParentTestClassFile(parentClass, testSourceDirectory);
                    writeBuggyJavaFile(className, methodName, parentClassFile, parentDirectory);
                    return;
                }
                getLog().warn("Test method not found: " + methodName + " in " + className);
                return;
            }

            // Write reduced test source code
            File subDirectory = new File(parentDirectory + File.separator + className + "." + methodName);
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
