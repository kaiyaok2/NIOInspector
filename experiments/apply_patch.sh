#!/bin/bash

folder_name=$(basename "$(pwd)")
class_name=$(echo "$folder_name" | awk -F'.' '{print $(NF-1)}')
java_file_path=$(find $1/src/test/java/ -name "${class_name}.java" | head -n 1)

if [ -z "$java_file_path" ]; then
    echo "Java file for class ${class_name} not found."
    exit 1
else
    echo "Java file found at: $java_file_path"
fi

retry_count=0
max_retries=5
patch_success=false

while [ $retry_count -lt $max_retries ]; do
    if [ -f "generate_compilable_patch.py" ]; then
        if [[ "$2" == GPT* ]]; then
            python3 generate_compilable_patch.py "$class_name" "$java_file_path" "$2" "$3"
        elif [[ "$2" == DeepSeek* || "$2" == Qwen* ]]; then
            python3 generate_compilable_patch.py "$class_name" "$java_file_path" "$2"
        else
            echo "Unsupported model. Not applying patches."
        fi
    elif [ -f "../../../generate_compilable_patch.py" ]; then
        if [[ "$2" == GPT* ]]; then
            python3 ../../../generate_compilable_patch.py "$class_name" "$java_file_path" "$2" "$3"
        elif [[ "$2" == DeepSeek* || "$2" == Qwen* ]]; then
            python3 ../../../generate_compilable_patch.py "$class_name" "$java_file_path" "$2"
        else
            echo "Unsupported model. Not applying patches."
        fi
    else
        echo "Error: generate_compilable_patch.py not found in either the current directory or ../../../"
        exit 1
    fi

    if [ -f "${class_name}.java" ]; then
        echo -e "\n" >> "${class_name}.java"
        temp_file=$(mktemp)
        tr -d '\r' < "${class_name}.java" > "$temp_file"
        mv "$temp_file" "${class_name}.java"

        
        dir_path=$(dirname "$java_file_path")
        cp "${class_name}.java" "$dir_path"

        patch_success=true
        break
    else
        echo "Compilable patched test not generated. Retrying... ($((retry_count + 1))/$max_retries)"
        retry_count=$((retry_count + 1))
    fi
done

if [ "$patch_success" = false ]; then
    echo "Failed to generate a correct patch after $max_retries attempts."
    exit 1
fi
