package edu.illinois.NIOInspector.plugin.util.detection;

import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.MalformedURLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IsolatedURLClassLoaderTest {

    @Test
    public void testConstructor() throws MalformedURLException {
        URL[] urls = new URL[] { new URL("file:///test") };

        IsolatedURLClassLoader classLoader = new IsolatedURLClassLoader(urls);

        assertNotNull(classLoader);
        assertTrue(classLoader instanceof URLClassLoader);
    }

    @Test
    public void testLoadClass() throws Exception {
        URL[] urls = new URL[] { new URL("file:///test") };
        IsolatedURLClassLoader classLoader = new IsolatedURLClassLoader(urls);

        Class<?> loadedClass = classLoader.loadClass("java.lang.String");

        classLoader.close();
        
        assertNotNull(loadedClass);
        assertEquals("java.lang.String", loadedClass.getName());
    }

    @Test
    public void testIsolation() throws Exception {
        URL[] urls = new URL[] { new URL("file:///test") };
        IsolatedURLClassLoader classLoader = new IsolatedURLClassLoader(urls);

        Class<?> loadedClass = classLoader.loadClass("java.sql.Connection");

        classLoader.close();
        assertNotNull(loadedClass);
        assertEquals("java.sql.Connection", loadedClass.getName());

        // Ensure it is not loaded by the system class loader
        assertNotEquals(ClassLoader.getSystemClassLoader(), loadedClass.getClassLoader());
    }

    @Test
    public void testParentClassLoader() throws Exception {
        URL[] urls = new URL[] { };

        IsolatedURLClassLoader classLoader = new IsolatedURLClassLoader(urls);

        assertEquals(ClassLoader.getPlatformClassLoader(), classLoader.getParent());
        
        classLoader.close();
    }
}
