#!/bin/bash

# Check if log file name is provided as argument
if [ $# -eq 0 ]; then
    echo "Usage: $0 <log_file>"
    exit 1
fi

# Get the log file name from command-line argument
log_file="$1"

# Find the last line number containing "Failure message:"
start_line=$(grep -n "Failure message:" "$log_file" | cut -d':' -f1 | tail -n 1)

# Find the next line number starting with "[WARN]"
end_line=$(grep -n "\[WARN\]" "$log_file" | cut -d':' -f1 | grep -A1 "^$start_line$" | tail -n 1)

# Check if both start and end lines are found
if [ -n "$start_line" ] && [ -n "$end_line" ]; then
    # Extract the desired portion of the log, excluding the line with "[WARN]"
    sed -n "${start_line},$(($end_line - 1))p" "$log_file" > "extracted_stacktrace_${log_file}"
    echo "Extracted log saved to extracted_stacktrace_${log_file}"
else
    echo "Failed to extract log"
fi

