package edu.illinois.NIOInspector.plugin.util.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * A utility class for extracting class/interface/enum declarations and their code from Java source files.
 */
public class ClassCodeExtractor {

    /**
     * Extracts the names and code of classes/interfaces/enums in a given Java source file.
     * 
     * @param file The Java source file to extract classes/interfaces/enums from.
     * @return A map where the keys are fully qualified class/interface/enum names and the values are the class/interface/enum code.
     * @throws IOException If an I/O error occurs reading the file.
     */
    public static Map<String, String> extractClassesWithCode(File file) throws IOException {
        Map<String, String> classCodeMap = new HashMap<>();

        // Read the entire content of the Java source file into a string
        String fileContent = new String(Files.readAllBytes(file.toPath()));

        // Initialize the JavaParser and parse the file to get a CompilationUnit
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = parser.parse(file);
        CompilationUnit cu = parseResult.getResult().orElseThrow(() -> new IOException("Parsing failed"));

        // Visit the class, interface, and enum declarations in the compilation unit
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                super.visit(n, arg);
                processDeclaration(n, cu, fileContent, classCodeMap);
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                super.visit(n, arg);
                processDeclaration(n, cu, fileContent, classCodeMap);
            }
        }, null);

        return classCodeMap;
    }

    /**
     * Processes a class or interface declaration to extract its fully qualified name and code.
     * 
     * @param n The class or interface declaration node.
     * @param cu The compilation unit containing the declaration.
     * @param fileContent The content of the Java source file as a string.
     * @param classCodeMap The map to store fully qualified class/interface names and their code.
     */
    private static void processDeclaration(ClassOrInterfaceDeclaration n, CompilationUnit cu, String fileContent, Map<String, String> classCodeMap) {
        // Retrieve the package name, if present
        String packageName = cu.getPackageDeclaration()
                               .map(pd -> pd.getNameAsString() + ".")
                               .orElse("");

        // Combine the package name and class/interface name to form the fully qualified class/interface name
        String className = packageName + n.getFullyQualifiedName().orElse(n.getNameAsString());

        // Determine the starting and ending lines of the class/interface in the source file
        int classBegin = n.getBegin().get().line - 1;
        int classEnd = n.getEnd().get().line;

        // Extract the class/interface code from the source file content
        StringBuilder classCode = new StringBuilder();
        String[] fileLines = fileContent.split("\n");
        for (int i = classBegin; i < classEnd; i++) {
            classCode.append(fileLines[i]).append("\n");
        }

        // Store the fully qualified class/interface name and its code in the map
        classCodeMap.put(className, classCode.toString().trim());

        // Process nested classes/interfaces/enums
        n.getMembers().forEach(member -> {
            if (member instanceof ClassOrInterfaceDeclaration) {
                processDeclaration((ClassOrInterfaceDeclaration) member, cu, fileContent, classCodeMap);
            } else if (member instanceof EnumDeclaration) {
                processDeclaration((EnumDeclaration) member, cu, fileContent, classCodeMap);
            }
        });
    }

    /**
     * Processes an enum declaration to extract its fully qualified name and code.
     * 
     * @param n The enum declaration node.
     * @param cu The compilation unit containing the declaration.
     * @param fileContent The content of the Java source file as a string.
     * @param classCodeMap The map to store fully qualified enum names and their code.
     */
    private static void processDeclaration(EnumDeclaration n, CompilationUnit cu, String fileContent, Map<String, String> classCodeMap) {
        // Retrieve the package name, if present
        String packageName = cu.getPackageDeclaration()
                               .map(pd -> pd.getNameAsString() + ".")
                               .orElse("");

        // Combine the package name and enum name to form the fully qualified enum name
        String className = packageName + n.getFullyQualifiedName().orElse(n.getNameAsString());

        // Determine the starting and ending lines of the enum in the source file
        int classBegin = n.getBegin().get().line - 1;
        int classEnd = n.getEnd().get().line;

        // Extract the enum code from the source file content
        StringBuilder classCode = new StringBuilder();
        String[] fileLines = fileContent.split("\n");
        for (int i = classBegin; i < classEnd; i++) {
            classCode.append(fileLines[i]).append("\n");
        }

        // Store the fully qualified enum name and its code in the map
        classCodeMap.put(className, classCode.toString().trim());

        // Process nested classes/interfaces/enums
        n.getMembers().forEach(member -> {
            if (member instanceof ClassOrInterfaceDeclaration) {
                processDeclaration((ClassOrInterfaceDeclaration) member, cu, fileContent, classCodeMap);
            } else if (member instanceof EnumDeclaration) {
                processDeclaration((EnumDeclaration) member, cu, fileContent, classCodeMap);
            }
        });
    }

    public static void main(String[] args) {
        File file = new File("/Users/kekaiyao/Desktop/NIOInspector/src/main/java/edu/illinois/NIOInspector/plugin/util/extractors/MostRecentLogFinder.java");
        try {
            Map<String, String> classCodeMap = extractClassesWithCode(file);
            classCodeMap.forEach((k, v) -> System.out.println(k + " => " + v));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
