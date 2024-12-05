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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DownloadFixerMojoTest {

    private DownloadFixerMojo mojo;
    private Log mockLog;
    private Map<String, File> tempFiles;
    private File nioInspectorDir;

    @BeforeEach
    public void setUp() throws Exception {
        mojo = new DownloadFixerMojo() {
            @Override
            protected HttpURLConnection openConnection(URL url) throws IOException {
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

        // Create temporary files for each download
        tempFiles = new HashMap<>();
        String[] fileNames = {"fixer.py", "apply_patch.sh", "generate_compilable_patch.py"};
        for (String fileName : fileNames) {
            File tempFile = new File(nioInspectorDir, fileName);
            tempFile.deleteOnExit();
            tempFiles.put(fileName, tempFile);
        }

        setPrivateField(mojo, "fileUrls", new String[]{
            "https://example.com/fixer.py",
            "https://example.com/apply_patch.sh",
            "https://example.com/generate_compilable_patch.py"
        });
        setPrivateField(mojo, "fileNames", fileNames);
    }

    @Test
    void testExecute() throws MojoExecutionException {
        mojo.execute();

        // Verify all files are downloaded correctly
        for (Map.Entry<String, File> entry : tempFiles.entrySet()) {
            File file = new File(nioInspectorDir, entry.getKey());
            assertTrue(file.exists(), "The file " + file.getName() + " should be downloaded.");
            assertTrue(file.length() > 0, "The file " + file.getName() + " should not be empty.");
        }

        // Capture and verify log messages
        ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLog, atLeastOnce()).info(logCaptor.capture());
        for (String logMessage : logCaptor.getAllValues()) {
            assertTrue(
                logMessage.contains("File downloaded successfully: "),
                "Log message should indicate successful download."
            );
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
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
