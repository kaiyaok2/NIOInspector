package edu.illinois.NIOInspector.plugin.util.extractors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class ClassCodeExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testExtractClassesWithCode_singleClass() throws IOException {
        File tempFile = createTempJavaFile("SingleClass.java", "package test;\n\npublic class SingleClass {\n\n}");
        Map<String, String> result = ClassCodeExtractor.extractClassesWithCode(tempFile);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("test.SingleClass"));
        assertEquals("public class SingleClass {\n\n}", result.get("test.SingleClass"));
    }

    @Test
    public void testExtractClassesWithCode_nestedClasses() throws IOException {
        File tempFile = createTempJavaFile("NestedClasses.java",
                "package test;\n\npublic class OuterClass {\n\npublic class InnerClass {\n\n}\n\n}");
        Map<String, String> result = ClassCodeExtractor.extractClassesWithCode(tempFile);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("test.OuterClass"));
        assertTrue(result.containsKey("test.OuterClass.InnerClass"));
        assertEquals("public class OuterClass {\n\npublic class InnerClass {\n\n}\n\n}", result.get("test.OuterClass"));
        assertEquals("public class InnerClass {\n\n}", result.get("test.OuterClass.InnerClass"));
    }

    @Test
    public void testExtractClassesWithCode_enum() throws IOException {
        File tempFile = createTempJavaFile("EnumClass.java", "package test;\n\npublic enum EnumClass {\n\nA, B, C;\n\n}");
        Map<String, String> result = ClassCodeExtractor.extractClassesWithCode(tempFile);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("test.EnumClass"));
        assertEquals("public enum EnumClass {\n\nA, B, C;\n\n}", result.get("test.EnumClass"));
    }

    @Test
    public void testExtractClassesWithCode_abstractClass() throws IOException {
        File tempFile = createTempJavaFile("AbstractClass.java", "package test;\n\npublic abstract class AbstractClass {\n\n}");
        Map<String, String> result = ClassCodeExtractor.extractClassesWithCode(tempFile);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("test.AbstractClass"));
        assertEquals("public abstract class AbstractClass {\n\n}", result.get("test.AbstractClass"));
    }

    @Test
    public void testExtractClassesWithCode_interface() throws IOException {
        File tempFile = createTempJavaFile("Interface.java", "package test;\n\npublic interface Interface {\nvoid run();\n}");
        Map<String, String> result = ClassCodeExtractor.extractClassesWithCode(tempFile);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("test.Interface"));
        assertEquals("public interface Interface {\nvoid run();\n}", result.get("test.Interface"));
    }

    @Test
    public void testExtractClassesWithVariousNestedTypes() throws IOException {
        String javaSource = "package test;\n" +
                            "public class OuterClass {\n" +
                            "    public class InnerClass {\n" +
                            "    }\n" +
                            "    public static class StaticClass {\n" +
                            "    }\n" +
                            "    public interface InnerInterface {\n" +
                            "    }\n" +
                            "    public enum InnerEnum {\n" +
                            "        VALUE1, VALUE2;\n" +
                            "    }\n" +
                            "}";

        File tempFile = createTempJavaFile("Various.java", javaSource);

        Map<String, String> classCodeMap = ClassCodeExtractor.extractClassesWithCode(tempFile);

        assertEquals(5, classCodeMap.size());
        assertTrue(classCodeMap.containsKey("test.OuterClass"));
        assertTrue(classCodeMap.containsKey("test.OuterClass.InnerClass"));
        assertTrue(classCodeMap.containsKey("test.OuterClass.StaticClass"));
        assertTrue(classCodeMap.containsKey("test.OuterClass.InnerInterface"));
        assertTrue(classCodeMap.containsKey("test.OuterClass.InnerEnum"));
        assertFalse(classCodeMap.containsKey("test.InnerClass"));
        assertFalse(classCodeMap.containsKey("test.StaticClass"));
        assertFalse(classCodeMap.containsKey("test.InnerInterface"));
        assertFalse(classCodeMap.containsKey("test.InnerEnum"));

        String outerClassCode = "public class OuterClass {\n" +
                                "    public class InnerClass {\n" +
                                "    }\n" +
                                "    public static class StaticClass {\n" +
                                "    }\n" +
                                "    public interface InnerInterface {\n" +
                                "    }\n" +
                                "    public enum InnerEnum {\n" +
                                "        VALUE1, VALUE2;\n" +
                                "    }\n" +
                                "}";
        String innerClassCode = "public class InnerClass {\n" +
                                "    }";
        String staticClassCode = "public static class StaticClass {\n" +
                                "    }";
        String innerInterfaceCode = "public interface InnerInterface {\n" +
                                "    }";
        String innerEnumCode = "public enum InnerEnum {\n" +
                                "        VALUE1, VALUE2;\n" +
                                "    }";

        assertEquals(outerClassCode, classCodeMap.get("test.OuterClass"));
        assertEquals(innerClassCode, classCodeMap.get("test.OuterClass.InnerClass"));
        assertEquals(staticClassCode, classCodeMap.get("test.OuterClass.StaticClass"));
        assertEquals(innerInterfaceCode, classCodeMap.get("test.OuterClass.InnerInterface"));
        assertEquals(innerEnumCode, classCodeMap.get("test.OuterClass.InnerEnum"));
    }


    private File createTempJavaFile(String fileName, String content) throws IOException {
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(content);
        }
        return tempFile;
    }
}
