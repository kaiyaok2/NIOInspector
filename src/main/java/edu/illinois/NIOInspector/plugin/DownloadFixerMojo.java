package edu.illinois.NIOInspector.plugin;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "downloadFixer")
public class DownloadFixerMojo extends AbstractMojo {

    @Parameter(property = "url", defaultValue = "https://raw.githubusercontent.com/kaiyaok2/NIOInspector/main/GPT_NIO_fixer.py")
    private String url;

    @Parameter(property = "outputFile", defaultValue = "GPT_NIO_fixer.py")
    private String outputFile;

    public void execute() throws MojoExecutionException {
        try {
            URL sourceUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) sourceUrl.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String newUrl = connection.getHeaderField("Location");
                connection.disconnect();
                sourceUrl = new URL(newUrl);
                connection = (HttpURLConnection) sourceUrl.openConnection();
                connection.connect();
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
}