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

        // Visit the declarations in the compilation unit
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

                // Process methods and constructors of the current class
                processMethodsAndConstructors(n, className, fileContent, methodCodeMap);

                // Process nested classes/interfaces
                processNestedClasses(n, cu, fileContent, methodCodeMap, className, packageName);

                
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                super.visit(n, arg);

                // Retrieve the package name, if present
                String packageName = cu.getPackageDeclaration()
                                       .map(pd -> pd.getNameAsString() + ".")
                                       .orElse("");

                // Combine the package name and enum name to form the fully qualified enum name
                String enumName = packageName + n.getNameAsString();

                // Process methods of the enum
                processEnumMethods(n, enumName, fileContent, methodCodeMap);

                // Process nested classes/interfaces within the enum
                processNestedEnumTypes(n, cu, fileContent, methodCodeMap, enumName, packageName);
            }

        }, null);

        return retainLongestKeys(methodCodeMap);
    }

    /**
     * Processes methods and constructors of a given class or interface.
     * Adds their code to the provided map, concatenating codes for overloaded methods and constructors.
     * 
     * @param n The class or interface declaration to process.
     * @param className The fully qualified class name of the declaration.
     * @param fileContent The content of the source file.
     * @param methodCodeMap The map to which method and constructor codes are added.
     */
    private static void processMethodsAndConstructors(ClassOrInterfaceDeclaration n, String className, String fileContent, Map<String, String> methodCodeMap) {
        // Visit each method declared within the class or interface
        n.getMethods().forEach(method -> {
            if (method.isPublic() || method.isAbstract()) { // Include abstract methods
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

    /**
     * Processes methods of a given enum.
     * Adds their code to the provided map, concatenating codes for overloaded methods.
     * 
     * @param n The enum declaration to process.
     * @param enumName The fully qualified enum name of the declaration.
     * @param fileContent The content of the source file.
     * @param methodCodeMap The map to which method codes are added.
     */
    private static void processEnumMethods(EnumDeclaration n, String enumName, String fileContent, Map<String, String> methodCodeMap) {
        // Visit each method declared within the enum
        n.getMethods().forEach(method -> {
            if (method.isPublic()) {
                // Form the fully qualified method name
                String methodName = enumName + "." + method.getNameAsString();
                
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

        // Enum constants do not have methods, but their methods can be extracted similarly if needed
    }

    /**
     * Processes nested classes or interfaces within a given class or interface.
     * Adds their methods and constructors to the provided map, ensuring no duplication of parent class entries.
     * 
     * @param n The class or interface declaration containing nested classes or interfaces.
     * @param cu The CompilationUnit containing the class or interface.
     * @param fileContent The content of the source file.
     * @param methodCodeMap The map to which method and constructor codes are added.
     * @param parentClassName The fully qualified name of the parent class.
     * @param packageName The fully qualified name of the package.
     */
    private static void processNestedClasses(ClassOrInterfaceDeclaration n, CompilationUnit cu, String fileContent, Map<String, String> methodCodeMap, String parentClassName, String packageName) {
        // Process nested classes/interfaces within the current class or interface
        n.getMembers().forEach(member -> {
            if (member instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration nestedClass = (ClassOrInterfaceDeclaration) member;
                
                // Form the fully qualified name for the nested class
                String nestedClassName = parentClassName + "." + nestedClass.getNameAsString();

                // Process methods and constructors of the nested class
                processMethodsAndConstructors(nestedClass, nestedClassName, fileContent, methodCodeMap);
                
                // Process further nested classes/interfaces within the nested class
                processNestedClasses(nestedClass, cu, fileContent, methodCodeMap, nestedClassName, packageName);
            } else if (member instanceof EnumDeclaration) {
                EnumDeclaration nestedEnum = (EnumDeclaration) member;
                
                // Form the fully qualified name for the nested enum
                String nestedEnumName = parentClassName + "." + nestedEnum.getNameAsString();

                // Process methods of the nested enum
                processEnumMethods(nestedEnum, nestedEnumName, fileContent, methodCodeMap);

                // Process further nested types within the nested enum
                processNestedEnumTypes(nestedEnum, cu, fileContent, methodCodeMap, nestedEnumName, packageName);
            }
        });
    }

    /**
     * Processes nested classes or interfaces within a given enum.
     * Adds their methods and constructors to the provided map.
     * 
     * @param n The enum declaration containing nested classes or interfaces.
     * @param cu The CompilationUnit containing the enum.
     * @param fileContent The content of the source file.
     * @param methodCodeMap The map to which method and constructor codes are added.
     * @param parentEnumName The fully qualified name of the parent enum.
     * @param packageName The fully qualified name of the package.
     */
    private static void processNestedEnumTypes(EnumDeclaration n, CompilationUnit cu, String fileContent, Map<String, String> methodCodeMap, String parentEnumName, String packageName) {
        // Process nested classes/interfaces within the current enum
        n.getMembers().forEach(member -> {
            if (member instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration nestedClass = (ClassOrInterfaceDeclaration) member;
                
                // Form the fully qualified name for the nested class
                String nestedClassName = parentEnumName + "." + nestedClass.getNameAsString();

                // Process methods and constructors of the nested class
                processMethodsAndConstructors(nestedClass, nestedClassName, fileContent, methodCodeMap);

                // Process further nested classes/interfaces within the nested class
                processNestedClasses(nestedClass, cu, fileContent, methodCodeMap, nestedClassName, packageName);
            } else if (member instanceof EnumDeclaration) {
                EnumDeclaration nestedEnum = (EnumDeclaration) member;
                
                // Form the fully qualified name for the nested enum
                String nestedEnumName = parentEnumName + "." + nestedEnum.getNameAsString();

                // Process methods of the nested enum
                processEnumMethods(nestedEnum, nestedEnumName, fileContent, methodCodeMap);

                // Process further nested types within the nested enum
                processNestedEnumTypes(nestedEnum, cu, fileContent, methodCodeMap, nestedEnumName, packageName);
            }
        });
    }

    /**
     * Processes a map to retain only the key with the longest value for each unique value.
     * All other key-value pairs with the same value but shorter keys are removed.
     * 
     * @param inputMap The map to process, where keys are strings and values are strings.
     * @return A map containing only the longest key for each unique value.
     */
    public static Map<String, String> retainLongestKeys(Map<String, String> inputMap) {
        Map<String, String> resultMap = new HashMap<>();
        
        // Temporary map to track longest key for each value
        Map<String, String> valueToLongestKey = new HashMap<>();
        
        for (Map.Entry<String, String> entry : inputMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Check if the value is already in the map
            if (!valueToLongestKey.containsKey(value) || key.length() > valueToLongestKey.get(value).length()) {
                valueToLongestKey.put(value, key);
            }
        }

        // Populate resultMap with the longest keys for each value
        for (Map.Entry<String, String> entry : valueToLongestKey.entrySet()) {
            String longestKey = entry.getValue();
            resultMap.put(longestKey, inputMap.get(longestKey));
        }
        
        return resultMap;
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

