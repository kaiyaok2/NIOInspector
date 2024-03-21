package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
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
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
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

            // Convert the NIODetector path to a URL
            URL NIODetectorPluginURL = Paths.get(pluginPath).toUri().toURL();

            // Convert project test classes path to a URL
            URL testClassURL = new File(project.getBuild().getTestOutputDirectory()).toURI().toURL();

            // Path to the maven-plugin-api JAR file in the local Maven repository
            String mavenPluginApiJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/apache/maven/maven-plugin-api/3.8.1/maven-plugin-api-3.8.1.jar";

            // Path to the JUnit JAR file in the local Maven repository
            String junitJarPath = System.getProperty("user.home") +
                    "/.m2/repository/junit/junit/4.11/junit-4.11.jar";

            // Path to the Hamcrest JAR file in the local Maven repository
            String hamcrestJarPath = System.getProperty("user.home") +
                    "/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar";

            // Convert the paths to URLs
            URL mavenPluginApiJarUrl = new File(mavenPluginApiJarPath).toURI().toURL();
            URL junitJarUrl = new File(junitJarPath).toURI().toURL();
            URL hamcrestJarUrl = new File(hamcrestJarPath).toURI().toURL();

            classLoader = new IsolatedURLClassLoader(new URL[]{NIODetectorPluginURL, testClassURL, mavenPluginApiJarUrl, junitJarUrl, hamcrestJarUrl});
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Error creating URLClassLoader", e);
        }
    
        // Kick off the whole JUnit job from a class loaded by this ClassLoader
        try {
            // Load ClassLoaderIsolatedTestRunner using the IsolatedURLClassLoader
            Class<?> testRunnerClass = classLoader.loadClass(ClassLoaderIsolatedTestRunner.class.getName());

            // Get the default constructor
            Constructor<?> constructor = testRunnerClass.getDeclaredConstructor();

            // Ensure the constructor is accessible
            constructor.setAccessible(true);

            // Create an instance using the constructor
            Object testRunner = constructor.newInstance();
    
            // Invoke run_invokedReflectively method reflectively
            Method runMethod = testRunnerClass.getMethod("run_invokedReflectively", List.class, ClassLoader.class, int.class);
            runMethod.invoke(testRunner, testClassNames, classLoader, numReruns);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            System.out.println(e);
            e.printStackTrace();
            //throw new MojoExecutionException("Error invoking ClassLoaderIsolatedTestRunner", e);
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

