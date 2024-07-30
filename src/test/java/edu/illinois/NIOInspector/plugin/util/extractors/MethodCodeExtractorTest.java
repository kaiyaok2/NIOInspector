package edu.illinois.NIOInspector.plugin.util.extractors;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MethodCodeExtractorTest {

    private final Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).resolve("MethodCodeExtractorTest");

    public MethodCodeExtractorTest() throws IOException {
        Files.createDirectories(tempDir);
    }

    @Test
    public void testSinglePublicMethod() throws IOException {
        String javaSource = "package test;\n" +
                            "public class SingleMethodClass {\n" +
                            "    public void singlePublicMethod() {\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("SingleMethodClass.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertEquals(1, methodCodeMap.size());
        assertTrue(methodCodeMap.containsKey("test.SingleMethodClass.singlePublicMethod"));

        String singlePublicMethodCode = "    public void singlePublicMethod() {\n" +
                                        "    }";

        assertEquals(singlePublicMethodCode, methodCodeMap.get("test.SingleMethodClass.singlePublicMethod"));
    }

    @Test
    public void testPrivateMethod() throws IOException {
        String javaSource = "package test;\n" +
                            "public class PrivateMethodClass {\n" +
                            "    private void privateMethod() {\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("PrivateMethodClass.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertTrue(methodCodeMap.isEmpty());  // Private methods should not be included
    }

    @Test
    public void testProtectedMethod() throws IOException {
        String javaSource = "package test;\n" +
                            "public class ProtectedMethodClass {\n" +
                            "    protected void protectedMethod() {\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("ProtectedMethodClass.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertTrue(methodCodeMap.isEmpty());  // Protected methods should not be included
    }

    @Test
    public void testStaticMethod() throws IOException {
        String javaSource = "package test;\n" +
                            "public class StaticMethodClass {\n" +
                            "    public static void staticMethod() {\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("StaticMethodClass.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertEquals(1, methodCodeMap.size());
        assertTrue(methodCodeMap.containsKey("test.StaticMethodClass.staticMethod"));

        String staticMethodCode = "    public static void staticMethod() {\n" +
                                  "    }";

        assertEquals(staticMethodCode, methodCodeMap.get("test.StaticMethodClass.staticMethod"));
    }

    @Test
    public void testConstructor() throws IOException {
        String javaSource = "package test;\n" +
                            "public class ConstructorClass {\n" +
                            "    public ConstructorClass() {\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("ConstructorClass.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertEquals(1, methodCodeMap.size());
        assertTrue(methodCodeMap.containsKey("test.ConstructorClass.ConstructorClass"));

        String constructorCode = "    public ConstructorClass() {\n" +
                                 "    }";

        assertEquals(constructorCode, methodCodeMap.get("test.ConstructorClass.ConstructorClass"));
    }

    @Test
    public void testExtractImplementedMethodsWithVariousTypes() throws IOException {
        String javaSource = "package test;\n" +
                            "public class OuterClass {\n" +
                            "    public OuterClass() {\n" +
                            "    }\n" +
                            "    public void method1() {\n" +
                            "    }\n" +
                            "    public void method1(String arg) {\n" +
                            "    }\n" +
                            "    public static class StaticClass {\n" +
                            "        public StaticClass() {\n" +
                            "        }\n" +
                            "        public void staticMethod() {\n" +
                            "        }\n" +
                            "    }\n" +
                            "    public interface InnerInterface {\n" +
                            "        void interfaceMethod();\n" +
                            "    }\n" +
                            "    public enum InnerEnum {\n" +
                            "        VALUE1, VALUE2;\n" +
                            "        public void enumMethod() {\n" +
                            "        }\n" +
                            "    }\n" +
                            "}";

        File tempFile = tempDir.resolve("Test.java").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(javaSource);
        }

        Map<String, String> methodCodeMap = MethodCodeExtractor.extractImplementedMethodsWithCode(tempFile);

        assertEquals(5, methodCodeMap.size());
        assertTrue(methodCodeMap.containsKey("test.OuterClass.OuterClass"));
        assertTrue(methodCodeMap.containsKey("test.OuterClass.method1"));
        assertTrue(methodCodeMap.containsKey("test.OuterClass.StaticClass.StaticClass"));
        assertTrue(methodCodeMap.containsKey("test.OuterClass.StaticClass.staticMethod"));
        assertTrue(methodCodeMap.containsKey("test.OuterClass.InnerEnum.enumMethod"));

        String outerClassConstructorCode = "    public OuterClass() {\n" +
                                           "    }";
        String method1Code = "    public void method1() {\n" +
                             "    }\n" +
                             "    public void method1(String arg) {\n" +
                             "    }";
        String staticClassConstructorCode = "        public StaticClass() {\n" +
                                             "        }";
        String staticMethodCode = "        public void staticMethod() {\n" +
                                   "        }";
        String enumMethodCode = "        public void enumMethod() {\n" +
                                "        }";

        assertEquals(outerClassConstructorCode, methodCodeMap.get("test.OuterClass.OuterClass"));
        assertEquals(method1Code, methodCodeMap.get("test.OuterClass.method1"));
        assertEquals(staticClassConstructorCode, methodCodeMap.get("test.OuterClass.StaticClass.StaticClass"));
        assertEquals(staticMethodCode, methodCodeMap.get("test.OuterClass.StaticClass.staticMethod"));
        assertEquals(enumMethodCode, methodCodeMap.get("test.OuterClass.InnerEnum.enumMethod"));
    }

}
