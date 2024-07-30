package edu.illinois.NIOInspector.plugin.mojo;


import static edu.illinois.NIOInspector.plugin.util.extractors.ClassCodeExtractor.extractClassesWithCode;
import static edu.illinois.NIOInspector.plugin.util.extractors.MethodCodeExtractor.extractImplementedMethodsWithCode;
import static edu.illinois.NIOInspector.plugin.util.extractors.MostRecentLogFinder.findMostRecentLog;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Mojo to collect relevant source code following instructions from the LLM agent
 */
@Mojo(name = "collectRelevantSourceCode", defaultPhase = LifecyclePhase.INITIALIZE)
public class CollectRelevantSourceCodeMojo extends AbstractMojo {

    /**
     * Log file produced by running the Rerun Mojo
     */
    @Parameter(property = "logFile")
    private String logFilePath;

    /**
     * Directory to main source files, (i.e. src/main/...)
     */
    @Parameter(property = "sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    /**
     * Directory to test source files. (i.e. src/test/...)
     */
    @Parameter(property = "testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    /**
     * Maximum number of lines (of file-level source code) to be included in the prompt for LLM
     */
    public static int MAX_LINES_FROM_MOST_RELEVANT_FILE = 500;

    /**
     * Maximum number of relevant methods to be included in the prompt for LLM
     */
    public static int MAX_METHODS = 5;

    /**
     * Maximum number of relevant classes to be included in the prompt for LLM
     */
    public static int MAX_CLASSES = 2;
    
    /**
     * Executes the Mojo to collect source code information.
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
        List<String> possibleNIOTests = new ArrayList<>();
        try {
            try (BufferedReader reader = new BufferedReader(new FileReader(new File(parentDirectory, "possible-NIO-list.txt")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    possibleNIOTests.add(line.trim());
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        for (String possibleNIOTest : possibleNIOTests) {
            File agentResponse = new File(parentDirectory + File.separator +
                possibleNIOTest.replace("#", ".") + File.separator + "agent_response");
            if (!agentResponse.exists()) {
                throw new MojoExecutionException("No LLM-agent response found for " + possibleNIOTest +
                    ". Did you run `python3 GPT_NIO_fixer.py decide_relevant_source_code`?");
            }
            
            // Process the sorted list (e.g., write to file)
            String agentResponseString = "";
            try {
                agentResponseString = (new String(Files.readAllBytes(agentResponse.toPath()))).trim();
            } catch (IOException e) {
                throw new MojoExecutionException(e.getMessage());
            }

            // Write most relevant source code
            if (agentResponseString.equals("Directly Fixable")) {
                getLog().info("LLM Agent suggests that fixing " + possibleNIOTest + " is possible without additional source code info");
                continue;
            } else if (agentResponseString.startsWith("Find Method Code")) {
                String fullName = agentResponseString.substring(agentResponseString.indexOf(':') + 1).replaceAll("[{}\\s]", "");
                String className = splitString(fullName)[0];
                String methodName = splitString(fullName)[1];
                writeRelevantMethodCode(possibleNIOTest, className, methodName, parentDirectory);
            } else if (agentResponseString.startsWith("Find Class Code")) {
                String className = agentResponseString.substring(agentResponseString.indexOf(':') + 1).replaceAll("[{}\\s]", "");
                writeClassCode(possibleNIOTest, className, parentDirectory);
            } else if (agentResponseString.startsWith("Find Hypothesized Method")) {
                String methodName = agentResponseString.substring(agentResponseString.indexOf(':') + 1).replaceAll("[{}\\s]", "");
                writeRelevantMethodCode(possibleNIOTest, null, methodName, parentDirectory);
            } else if (agentResponseString.startsWith("Find Relevant File")) {
                writeMostRelevantFileCode(possibleNIOTest, parentDirectory);
            } else {
                getLog().warn("LLM agent fails to produce parsable response. Consider re-prompting the agent.");
            }
        }
    }

    /**
     * Writes the code of a class given the class name
     * @param possibleNIOTest The string containing class and method names of a possible NIO test.
     * @param methodOfInterest the name of the method to compare against.
     * @param parentDirectory the parent directory containing the source files.
     */
    private void writeClassCode(String possibleNIOTest, String className, String parentDirectory) {
        // Find all Java source files in the main + test directories
        List<File> sourceFiles = getListOfAllSourceFiles(sourceDirectory);
        List<File> testFiles = getListOfAllSourceFiles(testSourceDirectory);
        sourceFiles.addAll(testFiles);
        List<Map.Entry<String, String>> classCodeEntries = new ArrayList<>();

        for (File sourceFile : sourceFiles) {
            Map<String, String> classCodeMap;
            try {
                classCodeMap = extractClassesWithCode(sourceFile);
                classCodeEntries.addAll(classCodeMap.entrySet());
            } catch (IOException e) {
                getLog().warn("Failed to extract class code in " + sourceFile.toString());
            }
        }

        // Sort the names of classes found w.r.t. edit distance from input class name
        classCodeEntries.sort(Comparator.comparingInt(entry -> 
            levenshteinDistance(getSimpleMethodName(entry.getKey()), className)));

        // Locate the folder to write source file content
        String NIOTestName = possibleNIOTest.replace("#", ".");
        File subDirectory = new File(parentDirectory + File.separator + NIOTestName);
        if (!subDirectory.exists()) {
            subDirectory.mkdir();
        }
        
        // Process the sorted list (e.g., write to file)
        File outputFile = new File(subDirectory, "sourceCode");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            boolean exactMatchFound = false;
            int classesWritten = 0;
            for (Map.Entry<String, String> entry : classCodeEntries) {
                String clazz = entry.getKey();
                if (getSimpleMethodName(clazz).equals(className)) {
                    String classCode = entry.getValue();
                    writer.write("// This is the class code for " + clazz + "(): " + System.lineSeparator());
                    writer.write(classCode + System.lineSeparator());
                    exactMatchFound = true;
                    classesWritten++;
                } else {
                    if (exactMatchFound) {
                        break;
                    } else {
                        getLog().warn("No exact match found for given classname. Use most similar named class");
                        String classCode = entry.getValue();
                        writer.write("// This is the class code for " + clazz + "(): " + System.lineSeparator());
                        writer.write(classCode + System.lineSeparator());
                        break;
                    }
                }
                if (classesWritten > MAX_CLASSES) {
                    break;
                }
            }
        } catch (Exception e) {
            getLog().error("Error writing class content in source file", e);
        }
    }
    
    /**
     * Writes the relevant method code sorted by the Levenshtein distance between the method name
     * and the specified method of interest.
     * @param possibleNIOTest The string containing class and method names of a possible NIO test.
     * @param methodOfInterest the name of the class that shall contain the method.
     * @param methodOfInterest the name of the method to compare against.
     * @param parentDirectory the parent directory containing the source files.
     */
    private void writeRelevantMethodCode(String possibleNIOTest, String classOfInterest, String methodOfInterest, String parentDirectory) {
        // Find all Java source files in the main+test directories
        List<File> sourceFiles = getListOfAllSourceFiles(sourceDirectory);
        List<File> testFiles = getListOfAllSourceFiles(testSourceDirectory);
        sourceFiles.addAll(testFiles);
        List<Map.Entry<String, String>> methodCodeEntries = new ArrayList<>();

        for (File sourceFile : sourceFiles) {
            Map<String, String> methodCodeMap;
            try {
                methodCodeMap = extractImplementedMethodsWithCode(sourceFile);
                methodCodeEntries.addAll(methodCodeMap.entrySet());
            } catch (IOException e) {
                getLog().warn("Failed to extract implemented methods in " + sourceFile.toString());
            }
        }

        // Sort the method strings w.r.t edit distance from input
        if (classOfInterest != null) {
            String methodNameWithClass = classOfInterest + "." + methodOfInterest;
            methodCodeEntries.sort(Comparator.comparingInt(entry -> 
                levenshteinDistance(getMethodNameWithClassAndWithoutPackage(entry.getKey()), methodNameWithClass)));
        } else {
            methodCodeEntries.sort(Comparator.comparingInt(entry -> 
                levenshteinDistance(getSimpleMethodName(entry.getKey()), methodOfInterest)));
        }

        // Locate the folder to write source file content
        String NIOTestName = possibleNIOTest.replace("#", ".");
        File subDirectory = new File(parentDirectory + File.separator + NIOTestName);
        if (!subDirectory.exists()) {
            subDirectory.mkdir();
        }
        
        // Process the sorted list (e.g., write to file)
        File outputFile = new File(subDirectory, "sourceCode");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            boolean exactMatchFoundWhenClassSpecified = false;
            int methodWritten = 0;
            for (Map.Entry<String, String> entry : methodCodeEntries) {
                String method = entry.getKey();
                if (classOfInterest != null) {
                    if ((methodWritten > 0) && exactMatchFoundWhenClassSpecified &&
                        getMethodNameWithClassAndWithoutPackage(method) != (classOfInterest + "." + methodOfInterest)) {
                        break;
                    } else if (getMethodNameWithClassAndWithoutPackage(method).trim().equals((classOfInterest + "." + methodOfInterest).trim())) {
                        exactMatchFoundWhenClassSpecified = true;
                    }
                }
                String methodCode = entry.getValue();
                writer.write("// This is the method code for " + method + "(): " + System.lineSeparator());
                writer.write(methodCode + System.lineSeparator());
                methodWritten++;
                if (methodWritten >= MAX_METHODS) {
                    break;
                }
            }
            if ((classOfInterest != null) && (exactMatchFoundWhenClassSpecified == false)) {
                getLog().warn("No matching for desired method. Use existing ones with most similar names.");
            }
        } catch (Exception e) {
            getLog().error("Error writing method content in source file: ", e);
        }
    }

    /**
     * Extracts the class+method name from a fully qualified method/class/interface/field/enum name.
     * 
     * For example, "edu.illinois.NIOInspector.plugin.mojo.RerunMojo.execute" -> "RerunMojo.execute"
     * 
     * @param fullyQualifiedName The fully qualified name string.
     * @return class + method name
     */
    private String getMethodNameWithClassAndWithoutPackage(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fullyQualifiedName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fullyQualifiedName;
        }
        int secondLastDotIndex = fullyQualifiedName.lastIndexOf('.', lastDotIndex - 1);
        if (secondLastDotIndex == -1) {
            return fullyQualifiedName;
        }
        return fullyQualifiedName.substring(secondLastDotIndex + 1);
    }
    
    /**
     * Extracts the simple name from a fully qualified method/class/interface/field/enum name.
     * 
     * @param fullyQualifiedName the fully qualified name, like `edu.illinois.NIOInspector.plugin.mojo.RerunMojo`
     * @return simple method name without the package and class path, like `RerunMojo`
     */
    private String getSimpleMethodName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fullyQualifiedName.lastIndexOf('.');
        return lastDotIndex == -1 ? fullyQualifiedName : fullyQualifiedName.substring(lastDotIndex + 1);
    }
    
    /**
     * Splits a string into two parts based on the last occurrence of the '.' character.
     *
     * @param input The input string to be split.
     * @return An array of two strings, where the first element is the part before the last '.' 
     *         and the second element is the part after the last '.'.
     *         If there is no '.' in the input string:
     *         the first element will be the entire input string and the second element will be an empty string.
     */
    private String[] splitString(String input) {
        int lastDotIndex = input.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return new String[] {input, ""};
        }
        String part1 = input.substring(0, lastDotIndex);
        String part2 = input.substring(lastDotIndex + 1);
        return new String[] {part1, part2};
    }
    
    /**
     * Writes the code from most relevant source file w.r.t one possible NIO test.
     * @param possibleNIOTest The string containing class and method names of a possible NIO test.
     * @param parentDirectory The parent directory of the files to be written.
     */
    private void writeMostRelevantFileCode(String possibleNIOTest, String parentDirectory) {
        // Get the name of the class and find most related source file
        String[] classPath = possibleNIOTest.split("#")[0].split("\\.");
        String className = classPath[classPath.length - 1];
        File sourceFile = null;
        try {
            sourceFile = findSourceFileForTestClass(className, sourceDirectory);
        } catch (Exception e) {
            getLog().error("Error finding most relevant source file.", e);
        }

        // Locate the folder to write source file content
        String NIOTestName = possibleNIOTest.replace("#", ".");
        File subDirectory = new File(parentDirectory + File.separator + NIOTestName);
        if (!subDirectory.exists()) {
            subDirectory.mkdir();
        }

        // Read the contents of the source file and write it to `sourceCode`
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < MAX_LINES_FROM_MOST_RELEVANT_FILE) {
                contentBuilder.append(line).append("\n");
                count++;
            }
        } catch (Exception e) {
            getLog().error("Error reading source file", e);
        }
        String sourceCode = contentBuilder.toString();
        File outputFile = new File(subDirectory, "sourceCode");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(sourceCode);
            getLog().info("Possible relevant source file for " + possibleNIOTest + " written.");
        } catch (Exception e) {
            getLog().error("Error writing content in source file", e);
        }
    }
    
    /**
     * Getter of a list of all Java source files located in a directory recursively
     * @param sourceDirectory the directory to search for source files
     * @return the list of all Java source files
     */
    private List<File> getListOfAllSourceFiles(File sourceDirectory) {
        List<File> sourceFiles = new ArrayList<>();
        List<File> directoriesToProcess = new ArrayList<>();
        directoriesToProcess.add(sourceDirectory);
        while (!directoriesToProcess.isEmpty()) {
            File currentDirectory = directoriesToProcess.remove(0);
            File[] files = currentDirectory.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.isDirectory() || file.getName().endsWith(".java");
                }
            });

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        directoriesToProcess.add(file);
                    } else {
                        sourceFiles.add(file);
                    }
                }
            }
        }
        return sourceFiles;
    }
    
    /**
     * Finds the most relevant source file for a test class using the Levenshtein distance.
     * @param testClassName the name of the test class
     * @param sourceDirectory the directory to search for source files
     * @return the most relevant source file, or null if no match is found
     * @throws IOException if an I/O error occurs
     */
    private File findSourceFileForTestClass(String testClassName, File sourceDirectory) throws IOException {
        // Collect all source files
        List<File> sourceFiles = getListOfAllSourceFiles(sourceDirectory);

        // Extract base name of test class
        String baseTestClassName = testClassName.replaceAll("Test$", "").replaceAll("TestCase$", "");

        // Find best match using Levenshtein Distance
        File bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (File file : sourceFiles) {
            String fileName = file.getName();
            String baseFileName = fileName.replace(".java", "");
            int score = levenshteinDistance(baseTestClassName, baseFileName);

            if (score < bestScore) {
                bestScore = score;
                bestMatch = file;
            }
        }

        return bestMatch;
    }
    
    /**
     * Calculates the Levenshtein distance(# single-character edits) between 2 strings
     * @param a the first string
     * @param b the second string
     * @return the Levenshtein distance between the two strings
     */
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        // dynamic programming with memoization
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                        dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1),
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1)
                    );
                }
            }
        }

        return dp[a.length()][b.length()];
    }

}
