package edu.illinois.NIOInspector.plugin.util.logging;

import ch.qos.logback.core.Layout;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

public class CustomTimeBasedFileAppenderTest {

    private CustomTimeBasedFileAppender<String> appender;
    private LayoutWrappingEncoder<String> encoder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        appender = new CustomTimeBasedFileAppender<>();
        encoder = new LayoutWrappingEncoder<>();
        Layout<String> mockLayout = mock(Layout.class);
        encoder.setLayout(mockLayout);
        appender.setEncoder(encoder);
    }

    @Test
    public void testStartWithFileName() {
        String fileName = "logs/current-time-log";
        appender.setFile(fileName);

        appender.start();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
        String expectedFilePath = "logs/" + timestamp + "-log.log";
        assertEquals(expectedFilePath, appender.getFile());
    }

}
