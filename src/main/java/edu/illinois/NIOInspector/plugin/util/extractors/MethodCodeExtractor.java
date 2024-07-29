package edu.illinois.NIOInspector.plugin.util.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * A utility class for extracting method declarations and their code from Java source files.
 */
public class MethodCodeExtractor {

    /**
     * Extracts the names and code of public methods and constructors implemented in a given Java source file.
     * Returns a "function name" - "function code" map.
     * Code for overloaded methods and constructors of the same name are concatenated together.
     * 
     * @param file The Java source file to extract methods and constructors from.
     * @return A map where the keys are fully qualified method or constructor names and the values are concatenated codes.
     * @throws IOException If an I/O error occurs reading the file.
     */
    public static Map<String, String> extractImplementedMethodsWithCode(File file) throws IOException {
        Map<String, String> methodCodeMap = new HashMap<>();

        // Read the entire content of the Java source file into a string
        String fileContent = new String(Files.readAllBytes(file.toPath()));

        // Initialize the JavaParser and parse the file to get a CompilationUnit
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = parser.parse(file);
        CompilationUnit cu = parseResult.getResult().orElseThrow(() -> new IOException("Parsing failed"));

        // Visit the class or interface declarations in the compilation unit
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                super.visit(n, arg);

                // Retrieve the package name, if present
                String packageName = cu.getPackageDeclaration()
                                       .map(pd -> pd.getNameAsString() + ".")
                                       .orElse("");

                // Combine the package name and class name to form the fully qualified class name
                String className = packageName + n.getNameAsString();

                // Visit each method declared within the class or interface
                n.getMethods().forEach(method -> {
                    if (method.isPublic()) {
                        // Form the fully qualified method name
                        String methodName = className + "." + method.getNameAsString();
                        
                        // Determine the starting and ending lines of the method in the source file
                        int methodBegin = method.getBegin().get().line - 1;
                        int methodEnd = method.getEnd().get().line;

                        // Extract the method code from the source file content
                        String methodCode = fileContent.split("\n")[methodBegin];
                        for (int i = methodBegin + 1; i < methodEnd; i++) {
                            methodCode += "\n" + fileContent.split("\n")[i];
                        }

                        // Concatenate the method code to the existing value in the map
                        methodCodeMap.merge(methodName, methodCode, (existingCode, newCode) -> existingCode + "\n" + newCode);
                    }
                });

                // Visit each constructor declared within the class or interface
                n.getConstructors().forEach(constructor -> {
                    if (constructor.isPublic()) {
                        // Form the fully qualified constructor name
                        String constructorName = className + "." + n.getNameAsString();
                        
                        // Determine the starting and ending lines of the constructor in the source file
                        int constructorBegin = constructor.getBegin().get().line - 1;
                        int constructorEnd = constructor.getEnd().get().line;

                        // Extract the constructor code from the source file content
                        String constructorCode = fileContent.split("\n")[constructorBegin];
                        for (int i = constructorBegin + 1; i < constructorEnd; i++) {
                            constructorCode += "\n" + fileContent.split("\n")[i];
                        }

                        // Concatenate the constructor code to the existing value in the map
                        methodCodeMap.merge(constructorName, constructorCode, (existingCode, newCode) -> existingCode + "\n" + newCode);
                    }
                });
            }
        }, null);

        return methodCodeMap;
    }

    public static void main(String[] args) {
        File file = new File("/Users/kekaiyao/Desktop/PRs/123/simple-java-maven-app/src/main/java/com/mycompany/app/App.java");
        try {
            Map<String, String> methodCodeMap = extractImplementedMethodsWithCode(file);
            methodCodeMap.forEach((k, v) -> System.out.println(k + " => " + v));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
