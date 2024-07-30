package edu.illinois.NIOInspector.plugin.mojo;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Mojo to download the Python script (as needed to invoke GPT-4 API):
 */
@Mojo(name = "downloadFixer")
public class DownloadFixerMojo extends AbstractMojo {

    @Parameter(property = "url", defaultValue = "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/GPT_NIO_fixer.py")
    private String url;

    @Parameter(property = "outputFile", defaultValue = "GPT_NIO_fixer.py")
    private String outputFile;

    /**
     * Executes the Mojo to download the LLM agent script.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {
        try {
            URL sourceUrl = new URL(url);
            HttpURLConnection connection = openConnection(sourceUrl);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String newUrl = connection.getHeaderField("Location");
                connection.disconnect();
                sourceUrl = new URL(newUrl);
                connection = openConnection(sourceUrl);
            }

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                getLog().info("File downloaded successfully to: " + outputFile);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred while downloading file", e);
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
