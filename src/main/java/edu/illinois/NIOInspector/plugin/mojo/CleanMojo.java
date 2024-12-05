package edu.illinois.NIOInspector.plugin.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;

import java.io.File;

/**
 * Mojo to clean up files produced by NIOInspector
 */
@Mojo(name = "clean", defaultPhase = LifecyclePhase.CLEAN)
public class CleanMojo extends AbstractMojo {

    /**
     * Reference to the current Maven project we're rerunning tests on
     */
    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File projectBaseDirectory;

    /**
     * Setter for projectBaseDirectory to use in tests
     * @param projectBaseDirectory the base directory of the project
     */
    public void setProjectBaseDirectory(File projectBaseDirectory) {
        this.projectBaseDirectory = projectBaseDirectory;
    }
    
    /**
     * Check if the .NIOInspector directory exists, delete it if so
     */
    public void execute() {
        File nioInspectordirectory = new File(projectBaseDirectory, ".NIOInspector");
        if (nioInspectordirectory.exists() && nioInspectordirectory.isDirectory()) {
            deleteDirectory(nioInspectordirectory);
            getLog().info("Deleted .NIOInspector directory.");
        } else {
            getLog().warn(".NIOInspector directory does not exist.");
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
