package edu.illinois.NIOInspector.plugin.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

public class CollectRelevantSourceCodeMojoTest {

    private CollectRelevantSourceCodeMojo mojo;
    private Log mockLog;
    private File tempDir;

    @BeforeEach
    public void setUp() throws Exception {
        mojo = new CollectRelevantSourceCodeMojo();
        setPrivateField(mojo, "sourceDirectory", new File("src/main/java"));
        setPrivateField(mojo, "testSourceDirectory", new File("src/test/java"));
        setPrivateField(mojo, "logFilePath", null);
        mockLog = mock(Log.class);
        mojo.setLog(mockLog);

        tempDir = Files.createTempDirectory("testDir").toFile();
        tempDir.mkdirs();  // Ensure the directory is created
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Delete the temporary directory and its contents
        if (tempDir != null && tempDir.exists()) {
            Files.walk(tempDir.toPath())
                 .sorted((p1, p2) -> p2.compareTo(p1))  // Sort in reverse order to delete files before directories
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         e.printStackTrace();
                     }
                 });
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        field.setAccessible(false);
    }

    @Test
    public void testGetMethodNameWithClassAndWithoutPackage() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("getMethodNameWithClassAndWithoutPackage", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(mojo, "anonymized.path.plugin.mojo.CollectRelevantSourceCodeMojo.execute");
        method.setAccessible(false);
        assertEquals("CollectRelevantSourceCodeMojo.execute", result);
    }

    @Test
    public void testGetSimpleMethodName() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("getSimpleMethodName", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(mojo, "anonymized.path.plugin.mojo.CollectRelevantSourceCodeMojo");
        method.setAccessible(false);
        assertEquals("CollectRelevantSourceCodeMojo", result);
    }

    @Test
    public void testSplitString() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("splitString", String.class);
        method.setAccessible(true);

        String[] result = (String[]) method.invoke(mojo, "anonymized.path.plugin.mojo.CollectRelevantSourceCodeMojo");
        method.setAccessible(false);
        assertArrayEquals(new String[]{"anonymized.path.plugin.mojo", "CollectRelevantSourceCodeMojo"}, result);
    }

    @Test
    public void testWriteClassCode() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("writeClassCode", String.class, String.class, String.class);
        method.setAccessible(true);

        method.invoke(mojo, "testNIO", "TestClassName", tempDir.getAbsolutePath());

        method.setAccessible(false);
        verify(mockLog).warn(anyString());
    }

    @Test
    public void testWriteRelevantMethodCode() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("writeRelevantMethodCode", String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        method.invoke(mojo, "testNIO", "TestClassName", "testMethodName", tempDir.getAbsolutePath());

        method.setAccessible(false);
        verify(mockLog).warn(anyString());
    }

    @Test
    public void testWriteMostRelevantFileCode() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("writeMostRelevantFileCode", String.class, String.class);
        method.setAccessible(true);

        method.invoke(mojo, "testNIO", tempDir.getAbsolutePath());

        method.setAccessible(false);
        verify(mockLog).info(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetListOfAllSourceFiles() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("getListOfAllSourceFiles", File.class);
        method.setAccessible(true);

        Object resultObject = method.invoke(mojo, new File("src/main/java"));
        method.setAccessible(false);
        if (resultObject instanceof List<?>) {
            List<?> resultList = (List<?>) resultObject;
            if (!resultList.isEmpty() && resultList.get(0) instanceof File) {
                List<File> result = (List<File>) resultList;
                assertNotNull(result);
                assertFalse(result.isEmpty());
            } else {
                fail("Result does not match expected type List<File>");
            }
        } else {
            fail("Result is not of type List<?>");
        }
    }

    @Test
    public void testFindSourceFileForTestClass() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("findSourceFileForTestClass", String.class, File.class);
        method.setAccessible(true);

        File result = (File) method.invoke(mojo, "TestClass", new File("src/main/java"));
        method.setAccessible(false);
        assertNotNull(result);
    }

    @Test
    public void testLevenshteinDistance() throws Exception {
        Method method = CollectRelevantSourceCodeMojo.class.getDeclaredMethod("levenshteinDistance", String.class, String.class);
        method.setAccessible(true);

        int distance1 = (int) method.invoke(mojo, "kitten", "sitting");
        assertEquals(3, distance1);

        int distance2 = (int) method.invoke(mojo, "flaw", "lawn");
        assertEquals(2, distance2);

        int distance3 = (int) method.invoke(mojo, "intention", "execution");
        assertEquals(5, distance3);

        int distance4 = (int) method.invoke(mojo, "apple", "app|e");
        assertEquals(1, distance4);

        int distance5 = (int) method.invoke(mojo, "book", "b00k");
        assertEquals(2, distance5);

        int distance6 = (int) method.invoke(mojo, "kitten", "kitten");
        assertEquals(0, distance6);

        int distance7 = (int) method.invoke(mojo, "Sunday", "Saturday");
        assertEquals(3, distance7);

        method.setAccessible(false);
    }
}

