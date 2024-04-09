package edu.illinois.NIODetector.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Custom URL class loader that isolates loading of classes from the system class loader.
 */
public class IsolatedURLClassLoader extends URLClassLoader {

    /**
     * Constructs the isolated URL class loader on top of Platform classloader
     * to ensure non-core Java classes (e.g. java.sql.*) are loaded
     *
     * @param urls the URLs from which to load classes and resources
     */
    public IsolatedURLClassLoader(URL[] urls) {
        // Prevent delegation to the system class loader.
        super(urls, ClassLoader.getPlatformClassLoader());
    }
}
