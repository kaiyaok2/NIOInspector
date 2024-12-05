#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/github.com"
CSV_FILE=$1
APPLY_PATCH_SCRIPT="$SCRIPT_DIR/apply_patch.sh"
GENERATE_COMPILABLE_PATCH_SCRIPT="$SCRIPT_DIR/generate_compilable_patch.py"

if [[ ! -f "$CSV_FILE" ]]; then
    echo "Error: CSV file not found at $CSV_FILE"
    exit 1
fi

if [[ ! -f "$APPLY_PATCH_SCRIPT" ]]; then
    echo "Error: apply_patch.sh not found at $APPLY_PATCH_SCRIPT"
    exit 1
fi

tail -n +2 "$CSV_FILE" | while IFS=',' read -r project_url sha subproject_name fq_test_name; do
    echo "Processing project: $project_url, module: $subproject_name, test: $fq_test_name"
    project_slug=$(echo "$project_url" | sed 's|https://github.com/||')

    project_dir="$ROOT_DIR/$project_slug"
    if [[ ! -d "$project_dir" ]]; then
        echo "Error: Project directory $project_dir does not exist!"
        continue
    fi

    subproject_rel_path=$(cd "$project_dir" && mvn help:evaluate -Dexpression=project.basedir -pl :"$subproject_name" -q -DforceStdout 2>/dev/null)
    if [[ -z "$subproject_rel_path" ]]; then
        subproject_rel_path="."
    fi

    project_base_dir=$(cd "$project_dir" && mvn help:evaluate -Dexpression=project.basedir -q -DforceStdout 2>/dev/null)
    if [[ "$project_base_dir" == "$subproject_rel_path" ]]; then
        subproject_rel_path="."
    else
        subproject_rel_path=$(realpath --relative-to="$project_dir" "$subproject_rel_path")
    fi

    nio_inspector_path="$project_dir/.NIOInspector"
    module_path="$project_dir/$subproject_rel_path"

    # Replace '#' with '.' in the test name to match file path
    test_path=$(echo "$fq_test_name" | sed 's|#|.|')

    # Locate the most recent timestamp directory that contains the test path
    recent_dir=""
    while IFS= read -r -d '' timestamp_dir; do
        if [[ -d "$timestamp_dir/$test_path" ]]; then
            if [[ -z "$recent_dir" || "$timestamp_dir" -nt "$recent_dir" ]]; then
                recent_dir="$timestamp_dir"
            fi
        fi
    done < <(find "$nio_inspector_path" -maxdepth 1 -type d -print0)

    if [[ -z "$recent_dir" || ! -d "$recent_dir/$test_path" ]]; then
        echo "Error: No recent timestamp directory containing test $fq_test_name found in $nio_inspector_path"
        continue
    fi

    patch_dir="$recent_dir/$test_path"
    if [[ ! -d "$patch_dir" ]]; then
        echo "Error: Patch directory not found for test $fq_test_name at $patch_dir"
        continue
    fi

    patch_file="$patch_dir/patch.txt"
    if [[ ! -f "$patch_file" ]]; then
        echo "Error: Patch file not found at $patch_file"
        continue
    fi

    cp "$APPLY_PATCH_SCRIPT" "$patch_dir"
    cp "$GENERATE_COMPILABLE_PATCH_SCRIPT" "$patch_dir"
    if [[ $? -ne 0 ]]; then
        echo "Error: Failed to copy apply_patch.sh to $patch_dir"
        continue
    fi

    pushd "$patch_dir" > /dev/null || continue
    chmod +x ./apply_patch.sh
    if [[ "$2" == GPT* ]]; then
        ./apply_patch.sh "$module_path" "$2" "$3"
    elif [[ "$2" == DeepSeek* || "$2" == Qwen* ]]; then
        ./apply_patch.sh "$module_path" "$2"
    else
        echo "Unsupported model. Not applying patches."
    fi
    if [[ $? -ne 0 ]]; then
        echo "Error: Failed to apply patch for test $fq_test_name"
    fi
    popd > /dev/null
done