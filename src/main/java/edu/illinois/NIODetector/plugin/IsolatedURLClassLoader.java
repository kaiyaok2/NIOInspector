package edu.illinois.NIODetector.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;

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
        super(urls, null);
        try {
            loadSystemClasses();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads classes from the system class loader and defines them in this class loader.
     *
     * @throws NoSuchFieldException if a field is not found
     * @throws IllegalAccessException if access to a field is denied
     * @throws IOException if an I/O error occurs
     */
    private void loadSystemClasses() throws NoSuchFieldException, IllegalAccessException, IOException {
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        List<Class<?>> loadedClasses = getLoadedClasses(systemClassLoader);
        for (Class<?> clazz : loadedClasses) {
            byte[] classBytes = getClassBytes(clazz);
            defineClass(clazz.getName(), classBytes, 0, classBytes.length, clazz.getProtectionDomain());
        }
    }
    
    /**
     * Reads the bytes of a class file.
     *
     * @param clazz the class for which to read the bytes
     * @return the byte array representing the class file
     * @throws IOException if an I/O error occurs
     */
    private byte[] getClassBytes(Class<?> clazz) throws IOException {
        String classFilePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = ClassLoader.getSystemResourceAsStream(classFilePath)) {
            if (is == null) {
                throw new IOException("Class file not found for " + clazz.getName());
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        }
    }

    /**
     * Retrieves the list of loaded classes from the specified class loader.
     *
     * @param classLoader the class loader from which to retrieve loaded classes
     * @return the list of loaded classes
     * @throws NoSuchFieldException if a field is not found
     * @throws IllegalAccessException if access to a field is denied
     */
    private List<Class<?>> getLoadedClasses(ClassLoader classLoader) throws NoSuchFieldException, IllegalAccessException {
        Field classesField = ClassLoader.class.getDeclaredField("classes");
        classesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Class<?>> classes = (List<Class<?>>) classesField.get(classLoader);
        return new ArrayList<>(classes);
    }
}
