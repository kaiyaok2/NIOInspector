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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadFixerMojoTest {

    private DownloadFixerMojo mojo;
    private Log mockLog;
    private File tempFile;

    @BeforeEach
    public void setUp() throws Exception {
        mojo = new DownloadFixerMojo() {
            @Override
            protected HttpURLConnection openConnection(URL url) throws IOException {
                HttpURLConnection mockConnection = mock(HttpURLConnection.class);
                InputStream mockInputStream = getClass().getClassLoader().getResourceAsStream("test-script.py");
                
                if (mockInputStream == null) {
                    throw new RuntimeException("Resource /test-script.py not found.");
                }
                
                when(mockConnection.getInputStream()).thenReturn(mockInputStream);
                when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
                return mockConnection;
            }
        };

        mockLog = mock(Log.class);
        mojo.setLog(mockLog);

        tempFile = File.createTempFile("testDownloadFixer", ".py");
        tempFile.deleteOnExit();

        // Use reflection to set private fields
        setPrivateField(mojo, "url", "https://example.com/test-script.py");
        setPrivateField(mojo, "outputFile", tempFile.getAbsolutePath());
    }

    @Test
    void testExecute() throws MojoExecutionException {
        mojo.execute();

        // Verify the file is downloaded correctly
        assertTrue(tempFile.exists(), "The file should be downloaded.");
        assertTrue(tempFile.length() > 0, "The file should not be empty.");

        // Capture and verify log messages
        ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLog, times(1)).info(logCaptor.capture());
        assertTrue(logCaptor.getValue().contains("File downloaded successfully to:"), "Log message should indicate successful download.");
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        // Utility to set private fields via reflection
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in class hierarchy.");
    }
}
