#!/bin/bash

# Check if log file name is provided as argument
if [ $# -eq 0 ]; then
    echo "Usage: $0 <log_file>"
    exit 1
fi

# Get the log file name from command-line argument
log_file="$1"

# Find the line number of the "final results" line
start_line=$(grep -n "\[INFO\] =========================Final Results=========================" "$log_file" | cut -d':' -f1)

# Find the second line after the start line
target_line=$((start_line + 2))

# Check if the target line exists
total_lines=$(wc -l < "$log_file")
if ((target_line <= total_lines)); then
    # Get the content of the target line
    target_content=$(sed "${target_line}q;d" "$log_file")
    
    # Check if the target line starts with "[ERROR] " (happens only if possible NIO tests found)
    if echo "$target_content" | grep -q "^\[ERROR\] "; then
        # Extract the test - substring after "[ERROR] " and before " (passed in the initial run"
        error_string=$(echo "$target_content" | sed -E 's/^\[ERROR\] (.*) \(passed in the initial run.*/\1/')
        echo "$error_string"
    fi
fi

