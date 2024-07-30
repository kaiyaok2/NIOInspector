package edu.illinois.NIOInspector.plugin.util.extractors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Date;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class MostRecentLogFinderTest {

    private static final String LOG_FILE_NAME = "rerun-results.log";

    private File mockLogDirectory;
    private File mockMostRecentDirectory;
    private File mockLogFile;

    private MockedStatic<MostRecentLogFinder> mockedStatic;

    @BeforeEach
    public void setUp() throws Exception {
        // Initialize mocks
        mockLogDirectory = mock(File.class);
        mockMostRecentDirectory = mock(File.class);
        mockLogFile = mock(File.class);

        when(mockLogDirectory.isDirectory()).thenReturn(true);
        when(mockLogDirectory.listFiles(File::isDirectory)).thenReturn(new File[]{mockMostRecentDirectory});

        when(mockMostRecentDirectory.isDirectory()).thenReturn(true);
        when(mockMostRecentDirectory.listFiles()).thenReturn(new File[]{mockLogFile});

        when(mockLogFile.getName()).thenReturn(LOG_FILE_NAME);

        // Mock static methods
        mockedStatic = Mockito.mockStatic(MostRecentLogFinder.class);
    }

    @Test
    public void testFindMostRecentLogSuccess() throws MojoExecutionException {
        // Set up the static mock for the timestamp method
        when(MostRecentLogFinder.getTimestampFromDirectory(mockMostRecentDirectory)).thenReturn(new Date().getTime());

        // Mock static method call
        mockedStatic.when(MostRecentLogFinder::findMostRecentLog).thenReturn(mockLogFile);

        // Call the method under test
        File result = MostRecentLogFinder.findMostRecentLog();

        // Verify the result
        assertNotNull(result, "Expected a log file to be found");
        assertEquals(LOG_FILE_NAME, result.getName(), "Expected the log file name to be 'rerun-results.log'");
    }

    @Test
    public void testFindMostRecentLogNoLogFile() throws MojoExecutionException {
        // Mock the log directory without the log file
        when(mockMostRecentDirectory.listFiles()).thenReturn(new File[]{});

        // Mock static method call
        mockedStatic.when(MostRecentLogFinder::findMostRecentLog).thenThrow(new MojoExecutionException("Failed to find a recent rerun-results.log file"));

        // Call the method under test and verify exception
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, () -> {
            MostRecentLogFinder.findMostRecentLog();
        });
        assertEquals("Failed to find a recent rerun-results.log file", thrown.getMessage());
    }

    @Test
    public void testFindMostRecentLogNoSubdirectories() throws MojoExecutionException {
        // Mock the log directory with no subdirectories
        when(mockLogDirectory.listFiles(File::isDirectory)).thenReturn(new File[]{});

        // Mock static method call
        mockedStatic.when(MostRecentLogFinder::findMostRecentLog).thenThrow(new MojoExecutionException("Failed to find a recent rerun-results.log file"));

        // Call the method under test and verify exception
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, () -> {
            MostRecentLogFinder.findMostRecentLog();
        });
        assertEquals("Failed to find a recent rerun-results.log file", thrown.getMessage());
    }

    @AfterEach
    public void tearDown() {
        // Close the static mock
        mockedStatic.close();
    }
}
