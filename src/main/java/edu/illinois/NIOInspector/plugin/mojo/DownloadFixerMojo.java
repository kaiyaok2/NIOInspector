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
 * Mojo to download the required scripts (fixer.py, apply_patch.sh, generate_diff.py):
 */
@Mojo(name = "downloadFixer")
public class DownloadFixerMojo extends AbstractMojo {

    private final String[] fileUrls = {
        "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/fixer.py",
        "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/experiments/apply_patch.sh",
        "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/experiments/generate_compilable_patch.py"
    };

    private final String[] fileNames = {
        "fixer.py",
        "apply_patch.sh",
        "generate_compilable_patch.py"
    };

    /**
     * Executes the Mojo to download the required scripts.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {
        File nioInspectorDir = new File(".NIOInspector");
        if (!nioInspectorDir.exists() && !nioInspectorDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create directory: " + nioInspectorDir.getAbsolutePath());
        }

        for (int i = 0; i < fileUrls.length; i++) {
            downloadFile(fileUrls[i], new File(nioInspectorDir, fileNames[i]));
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

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(targetFile)) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                getLog().info("File downloaded successfully: " + targetFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred while downloading file: " + targetFile.getName(), e);
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

