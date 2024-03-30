#!/bin/bash

# Check if log file name is provided as argument
if [ $# -eq 0 ]; then
    echo "Usage: $0 <log_file>"
    exit 1
fi

# Get the log file name from command-line argument
log_file="$1"

# Run the first script to get all error strings
error_strings=$(./get_possible_NIO_list.sh "$log_file")

# Check if there are any error strings
if [ -z "$error_strings" ]; then
    echo "No error strings found"
    exit 0
fi

# Loop through each error string
while IFS= read -r error_string; do
    # Run the second script on each error string
    ./get_reduced_test_file.sh "$error_string"
done <<< "$error_strings"