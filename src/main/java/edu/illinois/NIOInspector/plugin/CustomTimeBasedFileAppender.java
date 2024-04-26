package edu.illinois.NIOInspector.plugin;

import ch.qos.logback.core.FileAppender;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Custom appender that ensures each log of a rerun task is saved in a time-named sub-directory
 */
public class CustomTimeBasedFileAppender<E> extends FileAppender<E> {
    @Override
    public void start() {
        if (fileName != null) {
            try {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
                // Constructing the file path with the current time as directory name
                String filePath = fileName.replace("current-time", timestamp) + ".log";
                setFile(filePath);
                super.start();
            } catch (Exception e) {
                addError("Failed to create log file", e);
            }
        } else {
            addError("The File property must be set before using this appender.");
        }
    }
}
