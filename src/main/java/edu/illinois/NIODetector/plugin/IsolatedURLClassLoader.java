package edu.illinois.NIODetector.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Custom URLClassLoader that isolates loading of classes from the system class loader.
 */
public class IsolatedURLClassLoader extends URLClassLoader {

    /**
     * Constructs an IsolatedURLClassLoader with the specified URLs.
     *
     * @param urls the URLs from which to load classes and resources
     */
    public IsolatedURLClassLoader(URL[] urls) {
        // Prevent delegation to the system class loader.
        super(urls, ClassLoader.getPlatformClassLoader());
    }
}

