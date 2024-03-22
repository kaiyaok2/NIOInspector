package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.hamcrest.CoreMatchers;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.List;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.launcher.Launcher;
import org.junit.runner.JUnitCore;
import java.util.ArrayList;

@Mojo(name = "rerun", requiresDependencyResolution = ResolutionScope.TEST)
public class RerunMojo extends AbstractMojo {

    @org.apache.maven.plugins.annotations.Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @org.apache.maven.plugins.annotations.Parameter(property = "numReruns", defaultValue = "3")
    private int numReruns;

    public void execute() throws MojoExecutionException {
        String groupId = "edu.illinois";
        String artifactId = "NIODetector";
        
        // Get current version of NIODetector
        String version = null;
        Properties props = new Properties();
        String path = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream != null) {
                props.load(stream);
                version = props.getProperty("version");
            } else {
                throw new MojoExecutionException("Could not find pom.properties file.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading pom.properties file", e);
        }

        // Find all test classes in current project to examine
        List<String> testClassNames = findTestClasses(project.getBuild().getTestOutputDirectory());
    
        URLClassLoader classLoader = null;
        try {
            // Directory of the local Maven repository
            String mavenRepoDir = System.getProperty("user.home") + "/.m2/repository";

            // Construct the path to the JAR file of the NIODetector jar
            String pluginPath = mavenRepoDir + "/" + groupId.replace('.', '/') + "/" + artifactId + 
                                "/" + version + "/" + artifactId + "-" + version + ".jar";

            // Path to the maven-plugin-api JAR file in the local Maven repository
            String mavenPluginAPIVersion = DependencyVersionExtractor.getVersion(MojoExecutionException.class);
            String mavenPluginApiJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/apache/maven/maven-plugin-api/" +
                    mavenPluginAPIVersion +
                    "/maven-plugin-api-" +
                    mavenPluginAPIVersion + 
                    ".jar";

            
            // Path to the JUnit5 platform JARs file in the local Maven repository
            String JUnit5PlatformVersion = DependencyVersionExtractor.getVersion(Launcher.class);
            String Junit5PlatformEngineJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/junit/platform/junit-platform-engine/" + 
                    JUnit5PlatformVersion +
                    "/junit-platform-engine-" + 
                    JUnit5PlatformVersion + 
                    ".jar";
            String Junit5PlatformLauncherJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/junit/platform/junit-platform-launcher/" + 
                    JUnit5PlatformVersion +
                    "/junit-platform-launcher-" + 
                    JUnit5PlatformVersion + 
                    ".jar";
            String Junit5PlatformCommonsJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/junit/platform/junit-platform-commons/" + 
                    JUnit5PlatformVersion +
                    "/junit-platform-commons-" + 
                    JUnit5PlatformVersion + 
                    ".jar"; 

            // Path to the JUnit5 API & Jupiter Engine JARs file in the local Maven repository
            String JUnit5APIAndEngineVersion = DependencyVersionExtractor.getVersion(ExecutionMode.class);
            String JunitJupiterEngineJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/junit/jupiter/junit-jupiter-engine/" + 
                    JUnit5APIAndEngineVersion +
                    "/junit-jupiter-engine-" + 
                    JUnit5APIAndEngineVersion + 
                    ".jar";
            String JunitJupiterAPIJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/junit/jupiter/junit-jupiter-api/" + 
                    JUnit5APIAndEngineVersion +
                    "/junit-jupiter-api-" + 
                    JUnit5APIAndEngineVersion + 
                    ".jar";
            
            // Path to the JUnit4 JAR file in the local Maven repository
            String JUnit4Version = DependencyVersionExtractor.getVersion(JUnitCore.class);
            String Junit4JarPath = System.getProperty("user.home") +
                    "/.m2/repository/junit/junit/" + 
                    JUnit4Version +
                    "/junit-" + 
                    JUnit4Version + 
                    ".jar";

            // Path to the Hamcrest JAR file (required for JUnit 4.11+) in the local Maven repository
            String hamcrestVersion = DependencyVersionExtractor.getVersion(CoreMatchers.class);
            String hamcrestJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/hamcrest/hamcrest-core/" +
                    hamcrestVersion + 
                    "/hamcrest-core-" + 
                    hamcrestVersion + 
                    ".jar";

            // Convert the paths to URLs
            URL NIODetectorPluginURL = Paths.get(pluginPath).toUri().toURL();
            URL testClassURL = new File(project.getBuild().getTestOutputDirectory()).toURI().toURL();
            URL mavenPluginApiJarUrl = new File(mavenPluginApiJarPath).toURI().toURL();
            URL Junit5PlatformEngineJarUrl = new File(Junit5PlatformEngineJarPath).toURI().toURL();
            URL Junit5PlatformLauncherJarUrl = new File(Junit5PlatformLauncherJarPath).toURI().toURL();
            URL Junit5PlatformCommonsJarUrl = new File(Junit5PlatformCommonsJarPath).toURI().toURL();
            URL JunitJupiterEngineJarUrl = new File(JunitJupiterEngineJarPath).toURI().toURL();
            URL JunitJupiterAPIJarUrl = new File(JunitJupiterAPIJarPath).toURI().toURL();
            URL Junit4JarUrl = new File(Junit4JarPath).toURI().toURL();
            URL hamcrestJarUrl = new File(hamcrestJarPath).toURI().toURL();

            classLoader = new IsolatedURLClassLoader(new URL[]{NIODetectorPluginURL, testClassURL, mavenPluginApiJarUrl, Junit5PlatformCommonsJarUrl, 
                Junit5PlatformEngineJarUrl, Junit5PlatformLauncherJarUrl, Junit4JarUrl, hamcrestJarUrl, JunitJupiterEngineJarUrl, JunitJupiterAPIJarUrl});
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Error creating URLClassLoader", e);
        }
    
        // Run the whole JUnit testing from a class loaded by this ClassLoader
        try {
            Class<?> testRunnerClass = classLoader.loadClass(ClassLoaderIsolatedTestRunner.class.getName());
            Constructor<?> constructor = testRunnerClass.getDeclaredConstructor();

            // Ensure the constructor is accessible and create an instance using the constructor
            constructor.setAccessible(true);
            Object testRunner = constructor.newInstance();
    
            // Invoke run_invokedReflectively method reflectively
            Method runMethod = testRunnerClass.getMethod("run_invokedReflectively", List.class, ClassLoader.class, int.class);
            runMethod.invoke(testRunner, testClassNames, classLoader, numReruns);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new MojoExecutionException("Error invoking ClassLoaderIsolatedTestRunner", e);
        }
    }

    private List<String> findTestClasses(String directory) {
        List<String> testClassNames = new ArrayList<>();
        findTestClassesRecursive(new File(directory), testClassNames, "");
        return testClassNames;
    }

    private void findTestClassesRecursive(File directory, List<String> testClassNames, String packageName) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findTestClassesRecursive(file, testClassNames, packageName + file.getName() + ".");
                } else if (file.getName().endsWith("Test.class")) {
                    String className = packageName + file.getName().replace(".class", "").replace(File.separator, ".");
                    testClassNames.add(className);
                }
            }
        }
    }
}

