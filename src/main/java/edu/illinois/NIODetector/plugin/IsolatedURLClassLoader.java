package edu.illinois.NIODetector.plugin;

import java.net.URL;
import java.net.URLClassLoader;

public class IsolatedURLClassLoader extends URLClassLoader {
    public IsolatedURLClassLoader(URL[] urls) {
        // Prevent delegation to the system class loader.
        super(urls, null);
    }
}

