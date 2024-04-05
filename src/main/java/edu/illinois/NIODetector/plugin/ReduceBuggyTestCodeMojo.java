
package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "reduceBuggyTestCode", defaultPhase = LifecyclePhase.INITIALIZE)
public class ReduceBuggyTestCodeMojo extends AbstractMojo {

    @Parameter(property = "logFile", required = true)
    private String logFilePath;

    @Parameter(property = "testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    public void execute() {
        File logFile = new File(logFilePath);
        if (logFile == null || !logFile.exists()) {
            getLog().error("Log file not found or not provided.");
            return;
        }

        List<String> errorStrings = getErrorStrings(logFile);

        // Check if there are any error strings
        if (errorStrings.isEmpty()) {
            getLog().info("No error strings found");
            return;
        }

        for (String errorString : errorStrings) {
            writeReducedTestFile(errorString);
        }
    }

    private List<String> getErrorStrings(File logFile) {
        List<String> errorStrings = new ArrayList<>();

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
                System.out.println(line);
                while (line != null && line.startsWith("[ERROR] ")) {
                    // Extract the error string
                    String errorString = line.substring("[ERROR] ".length()).split(" \\(passed in the initial run")[0];
                    errorStrings.add(errorString);
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            getLog().error("Error reading log file: " + logFile.getAbsolutePath(), e);
        }
        return errorStrings;
    }

    private File findParentTestClassFile(String parentClass, File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null; // Invalid directory
        }

        // Search for the file recursively
        for (File file : directory.listFiles()) {
            System.out.println(file);
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

        return null; // File not found
    }

    private void writeReducedTestFile(String errorString) {
        String[] errorParts = errorString.split("#");
        if (errorParts.length != 2) {
            getLog().warn("Invalid error string format: " + errorString);
            return;
        }

        String className = errorParts[0];
        String methodName = errorParts[1];

        File testFile = new File(testSourceDirectory, className.replace('.', File.separatorChar) + ".java");
        if (!testFile.exists()) {
            getLog().warn("Test file not found: " + testFile.getAbsolutePath());
            return;
        }
        writeBuggyJavaFile(className, methodName, testFile);
    }

    private void writeBuggyJavaFile(String className, String methodName, File testFile) {
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

            if (!testFound) {
                if (hasParentClass) {
                    File parentClassFile = findParentTestClassFile(parentClass, testSourceDirectory);
                    writeBuggyJavaFile(className, methodName, parentClassFile);
                    return;
                }
                getLog().warn("Test method not found: " + methodName + " in " + className);
                return;
            }

            System.out.println("########################################");
            System.out.println(testContent);
            System.out.println("########################################");

            String buggyJavaFileName = className + ".buggyjava";
            try (FileWriter writer = new FileWriter(new File(buggyJavaFileName))) {
                writer.write(testContent.toString().replaceAll("(?m)^\\s*$[\r\n]*", ""));
                getLog().info("Buggy Java file written to: " + buggyJavaFileName);
            } catch (IOException e) {
                getLog().error("Error writing to file: " + buggyJavaFileName, e);
            }
        } catch (IOException e) {
            getLog().error("Error reading file: " + testFile.getAbsolutePath(), e);
        }
    }
}
