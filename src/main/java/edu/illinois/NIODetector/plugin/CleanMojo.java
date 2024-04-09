package edu.illinois.NIODetector.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;

import java.io.File;

/**
 * Mojo to clean up files produced by NIODetector
 */
@Mojo(name = "clean", defaultPhase = LifecyclePhase.CLEAN)
public class CleanMojo extends AbstractMojo {

    /**
     * Reference to the current Maven project we're rerunning tests on
     */
    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File projectBaseDirectory;

    /**
     * Check if the .NIODetector directory exists, delete it if so
     */
    public void execute() {
        File nioDetectordirectory = new File(projectBaseDirectory, ".NIODetector");
        if (nioDetectordirectory.exists() && nioDetectordirectory.isDirectory()) {
            deleteDirectory(nioDetectordirectory);
            getLog().info("Deleted .NIODetector directory.");
        } else {
            getLog().info(".NIODetector directory does not exist.");
        }
    }

    /**
     * Recursively deletes everything in the given directory.
     * @param directory the directory to fully delete
     */
    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}
