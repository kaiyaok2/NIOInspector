package edu.illinois.NIOInspector.plugin.mojo;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import edu.illinois.NIOInspector.plugin.util.ClassLoaderIsolatedTestRunner;
import edu.illinois.NIOInspector.plugin.util.IsolatedURLClassLoader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Custom Maven plugin Mojo for rerunning tests.
 */
@Mojo(name = "rerun", requiresDependencyResolution = ResolutionScope.TEST)
public class RerunMojo extends AbstractMojo {

    /**
     * Comma-separated list of test classes and methods to rerun.
     */
    @Parameter(property = "test", defaultValue = "")
    private String test;

    /**
     * Reference to the current Maven project we're rerunning tests on
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Reference to the artifacts needed by NIOInspector
     */
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    /**
     * Number of reruns for failing tests.
     */
    @Parameter(property = "numReruns", defaultValue = "3")
    private int numReruns;


    /**
     * Executes the Mojo to rerun tests.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {

        List<String> testClassNames = new ArrayList<>();
        Map<String, List<String>> classStringToMethodsMap = new HashMap<>();
        if (!(test == null) && !test.isEmpty()) {
            // Parse the test parameter and extract class and method names
            String[] testClasses = test.split(",");
            for (String testClassOrMethod : testClasses) {
                if (testClassOrMethod.contains("#")) {
                    // Method name specified
                    String[] parts = testClassOrMethod.split("#");
                    String testClass = parts[0];
                    String testMethod = parts[1];

                    // Bind test methods to classes
                    List<String> testsInTestClass = new ArrayList<>();
                    if (classStringToMethodsMap.containsKey(testClass)) {
                        testsInTestClass = classStringToMethodsMap.get(testClass);
                    }
                    testsInTestClass.add(testMethod.trim());
                    classStringToMethodsMap.put(testClass, testsInTestClass);
                    if (!testClassNames.contains(testClassOrMethod)) {
                        testClassNames.add(testClass);
                    }
                } else {
                    // Only class name specified
                    testClassNames.add(testClassOrMethod.trim());
                }
            }
        } else {
            // If test parameter is not provided, find all test classes
            testClassNames = findTestClasses(project.getBuild().getTestOutputDirectory());
        }
    
        URLClassLoader classLoader = null;
        try {
            // Convert the paths to URLs
            URL testClassesURL = new File(project.getBuild().getTestOutputDirectory()).toURI().toURL();

            List<URL> allURLs = new ArrayList<>();
            try {
                // Get all dependencies needed for NIOInspector plugin
                List<URL> pluginDependencies = new ArrayList<>();
                for (Artifact artifact : pluginArtifacts) {
                    if (artifact.getFile() != null) {
                        pluginDependencies.add(artifact.getFile().toURI().toURL());
                    }
                }
                allURLs.addAll(pluginDependencies);

                // Get all test dependencies for current project
                List<String> projectTestDependenciesElements = project.getTestClasspathElements();
                List<URL> projectTestDependencies = new ArrayList<>();
                for (String dependency : projectTestDependenciesElements) {
                    projectTestDependencies.add(new File(dependency).toURI().toURL());
                }
                allURLs.addAll(projectTestDependencies);

                // Add runtime dependencies of current project
                List<String> projectRuntimeDependenciesElements = project.getRuntimeClasspathElements();
                List<URL> projectRuntimeDependencies = new ArrayList<>();
                for (String dependency : projectRuntimeDependenciesElements) {
                    projectRuntimeDependencies.add(new File(dependency).toURI().toURL());
                }
                allURLs.addAll(projectRuntimeDependencies);
            } catch (Exception e) {
                throw new MojoExecutionException("Error retrieving all dependent Jars", e);
            }
            // Add test classes found in current project
            allURLs.add(testClassesURL);

            // Create IsolatedURLClassLoader with all relevant URLs
            classLoader = new IsolatedURLClassLoader(allURLs.toArray(new URL[0]));
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating URLClassLoader", e);
        }
    
        // Run the whole JUnit testing from a class loaded by this ClassLoader
        try {
            // Load the ClassLoaderIsolatedTestRunner class using the custom class loader
            Class<?> testRunnerClass = classLoader.loadClass(ClassLoaderIsolatedTestRunner.class.getName());
            Constructor<?> constructor = testRunnerClass.getDeclaredConstructor();

            // Ensure the constructor is accessible and create an instance using the constructor
            constructor.setAccessible(true);
            Object testRunner = constructor.newInstance();
    
            // Invoke the JUnit runner method reflectively
            Method runMethod = testRunnerClass.getMethod("runInvokedReflectively", List.class, Map.class, ClassLoader.class, int.class);
            runMethod.invoke(testRunner, testClassNames, classStringToMethodsMap, classLoader, numReruns);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new MojoExecutionException("Error invoking ClassLoaderIsolatedTestRunner", e);
        }
    }

    /**
     * Finds test classes recursively in the given directory.
     *
     * @param directory the directory to search for test classes
     * @return list of test class names
     */
    private List<String> findTestClasses(String directory) {
        List<String> testClassNames = new ArrayList<>();
        findTestClassesRecursive(new File(directory), testClassNames, "");
        return testClassNames;
    }

    /**
     * Recursively finds test classes in the given directory.
     *
     * @param directory the directory to search for test classes
     * @param testClassNames list to collect test class names
     * @param packageName the package name of the current directory
     */
    private void findTestClassesRecursive(File directory, List<String> testClassNames, String packageName) {
        File[] files = directory.listFiles();
        List<String> excludePatterns = parseSurefireExcludes(project);
        List<Pattern> compiledExcludePatterns = new ArrayList<>();
        for (String pattern : excludePatterns) {
            String excludeRegex = pattern.replace("/", "\\.").replace(".java", "")
                .replace("*", ".*").replace("$", "\\$");
            compiledExcludePatterns.add(Pattern.compile(excludeRegex));
        }
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findTestClassesRecursive(file, testClassNames, packageName + file.getName() + ".");
                } else if (file.getName().endsWith(".class")) {
                    String className = packageName + file.getName().replace(".class", "").replace(File.separator, ".");
                    boolean shouldInclude = true;
                    for (Pattern pattern : compiledExcludePatterns) {
                        if (pattern.matcher(className).matches()) {
                            shouldInclude = false;
                        }
                    }
                    if (shouldInclude) {
                        testClassNames.add(className);
                    }
                }
            }
        }
    }

    public static List<String> parseSurefireExcludes(MavenProject project) {
        List<String> excludedList = new ArrayList<>();
        Plugin surefirePlugin = project.getPlugin("org.apache.maven.plugins:maven-surefire-plugin");
        if (surefirePlugin != null) {
            for (PluginExecution execution : surefirePlugin.getExecutions()) {
                Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();
                if (configuration != null) {
                    Xpp3Dom excludesNode = configuration.getChild("excludes");
                    if (excludesNode != null) {
                        for (Xpp3Dom excludeNode : excludesNode.getChildren("exclude")) {
                            String excludePattern = excludeNode.getValue();
                            excludedList.add(excludePattern);
                        }
                    }
                }
            }
        }
        return excludedList;
    }
}
