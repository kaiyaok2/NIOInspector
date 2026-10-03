package edu.illinois.NIOInspector.plugin.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DownloadFixerMojoTest {

    private DownloadFixerMojo mojo;
    private Log mockLog;
    private File nioInspectorDir;

    private final String[] fileNames = {"fixer.py", "react_agent.py"};

    @BeforeEach
    public void setUp() throws Exception {
        mojo = new DownloadFixerMojo() {
            @Override
            protected HttpURLConnection openConnection(URL url) throws IOException {
                // The GitHub fallback must not be reached when scripts are bundled
                HttpURLConnection mockConnection = mock(HttpURLConnection.class);
                String fileName = url.toString().substring(url.toString().lastIndexOf('/') + 1);
                InputStream mockInputStream = getClass().getClassLoader().getResourceAsStream("test-" + fileName);

                if (mockInputStream == null) {
                    throw new RuntimeException("Resource /test-" + fileName + " not found.");
                }

                when(mockConnection.getInputStream()).thenReturn(mockInputStream);
                when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
                return mockConnection;
            }
        };

        mockLog = mock(Log.class);
        mojo.setLog(mockLog);

        // Create a temporary directory for .NIOInspector
        nioInspectorDir = new File(".NIOInspector");
        nioInspectorDir.mkdirs();
        nioInspectorDir.deleteOnExit();

        for (String fileName : fileNames) {
            File installed = new File(nioInspectorDir, fileName);
            installed.delete();
            installed.deleteOnExit();
        }
    }

    @Test
    void testExecuteInstallsBundledScripts() throws MojoExecutionException {
        mojo.execute();

        // Verify all scripts are installed from the bundled plugin resources
        for (String fileName : fileNames) {
            File file = new File(nioInspectorDir, fileName);
            assertTrue(file.exists(), "The script " + file.getName() + " should be installed.");
            assertTrue(file.length() > 0, "The script " + file.getName() + " should not be empty.");
        }

        // Capture and verify log messages
        ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLog, atLeastOnce()).info(logCaptor.capture());
        for (String logMessage : logCaptor.getAllValues()) {
            assertTrue(
                logMessage.contains("Installed bundled script: ")
                    || logMessage.contains("File downloaded successfully: "),
                "Log message should indicate successful installation."
            );
        }
    }
}
