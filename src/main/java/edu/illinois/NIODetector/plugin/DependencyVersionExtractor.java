package edu.illinois.NIODetector.plugin;

public class DependencyVersionExtractor {

    public static String getVersion(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        if (pkg != null) {
            return pkg.getImplementationVersion();
        }
        return null;
    }
}