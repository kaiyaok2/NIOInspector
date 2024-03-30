#!/bin/bash

# Check if test method name is provided as argument
if [ $# -eq 0 ]; then
    echo "Usage: $0 <test_method>"
    exit 1
fi

# Extract class name and test method name from the provided test method
test_method="$1"
class_name=$(echo "$test_method" | cut -d'#' -f1)
method_name=$(echo "$test_method" | cut -d'#' -f2)

# Convert class name to directory path
class_dir=$(echo "$class_name" | tr '.' '/')
test_file="src/test/java/$class_dir.java"

# Check if test file exists
if [ ! -f "$test_file" ]; then
    echo "Test file not found: $test_file"
    exit 1
fi

# Read the content of the Java file
java_code=$(<"$test_file")

# Write the modified Java code to a new .buggyjava file
touch ${class_name}.buggyjava
buggyjava_file="${class_name}.buggyjava"
echo "$java_code" > "$buggyjava_file"

echo "Modified Java code written to: $buggyjava_file"



