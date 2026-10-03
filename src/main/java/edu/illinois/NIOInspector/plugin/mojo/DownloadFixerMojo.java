package edu.illinois.NIOInspector.plugin.mojo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Mojo to install the LLM fixer scripts (fixer.py, react_agent.py) into the
 * local .NIOInspector directory.
 *
 * The scripts are bundled as resources in the plugin jar, so the installed
 * scripts always match the plugin version in use. If a bundled copy is
 * missing (e.g. a stripped-down build of the plugin), the Mojo falls back to
 * downloading the scripts from the NIOInspector GitHub repository.
 */
@Mojo(name = "downloadFixer")
public class DownloadFixerMojo extends AbstractMojo {

    private String[] fileNames = {
        "fixer.py",
        "react_agent.py"
    };

    private String[] fileUrls = {
        "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/fixer.py",
        "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/react_agent.py"
    };

    /**
     * Executes the Mojo to install the required scripts.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {
        File nioInspectorDir = new File(".NIOInspector");
        if (!nioInspectorDir.exists() && !nioInspectorDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create directory: " + nioInspectorDir.getAbsolutePath());
        }

        for (int i = 0; i < fileNames.length; i++) {
            File targetFile = new File(nioInspectorDir, fileNames[i]);
            if (extractBundledScript(fileNames[i], targetFile)) {
                getLog().info("Installed bundled script: " + targetFile.getAbsolutePath());
            } else {
                downloadFile(fileUrls[i], targetFile);
            }
        }
    }

    /**
     * Copies a script bundled in the plugin jar (under /fixer-scripts) to the target file.
     *
     * @param scriptName the name of the bundled script
     * @param targetFile the file object representing the target location
     * @return true if the bundled script was found and copied, false otherwise
     * @throws MojoExecutionException if the bundled script exists but cannot be copied
     */
    private boolean extractBundledScript(String scriptName, File targetFile) throws MojoExecutionException {
        try (InputStream inputStream = getClass().getResourceAsStream("/fixer-scripts/" + scriptName)) {
            if (inputStream == null) {
                return false;
            }
            copyStream(inputStream, targetFile);
            return true;
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred while extracting bundled script: " + scriptName, e);
        }
    }

    /**
     * Downloads a file from the specified URL and saves it to the given file.
     *
     * @param fileUrl the URL of the file to download
     * @param targetFile the file object representing the target location
     * @throws MojoExecutionException if an error occurs during the download
     */
    private void downloadFile(String fileUrl, File targetFile) throws MojoExecutionException {
        try {
            URL sourceUrl = new URL(fileUrl);
            HttpURLConnection connection = openConnection(sourceUrl);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String newUrl = connection.getHeaderField("Location");
                connection.disconnect();
                sourceUrl = new URL(newUrl);
                connection = openConnection(sourceUrl);
            }

            try (InputStream inputStream = connection.getInputStream()) {
                copyStream(inputStream, targetFile);
                getLog().info("File downloaded successfully: " + targetFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred while downloading file: " + targetFile.getName(), e);
        }
    }

    /**
     * Copies an input stream to a target file.
     *
     * @param inputStream the stream to copy from
     * @param targetFile the file to write to
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream inputStream, File targetFile) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Opens a connection to the specified URL and prepares it for reading.
     * This method configures the connection to follow HTTP redirects automatically.
     *
     * @param url the URL to open a connection to
     * @return an {@link HttpURLConnection} object representing the connection to the URL
     * @throws IOException if an I/O error occurs while opening the connection or if the URL is malformed
     */
    protected HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        return connection;
    }
}
