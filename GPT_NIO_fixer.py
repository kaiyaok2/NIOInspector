import sys
from openai import OpenAI
import glob
import os

def read_buggy_java_file_and_stacktrace():
    current_directory = os.getcwd()
    log_files = glob.glob(current_directory + "/*.buggyjava")
    stacktrace_files = glob.glob(current_directory + "/extracted_stacktrace*")
    for file_path in log_files:
        with open(file_path, 'r') as file:
            buggy_java_test_contents = file.read()
            break
    for file_path in stacktrace_files:
        with open(file_path, 'r') as file:
            stacktrace_contents = file.read()
            break
    return buggy_java_test_contents, stacktrace_contents


client = OpenAI(api_key=sys.argv[1])

def generate_text(prompt_text, max_tokens, buggy_java_test, stacktrace):
    prompt = prompt_text +\
             "The test always passes at the first time but fails in all subsequent runs in the same JVM. " +\
             "In other words, the test has side effects and “self- pollutes” the state shared among test runs," +\
             "so only the first run succeeds:\n```\n" +\
             str(buggy_java_test) +\
             "```\n The error message is:\n```\n" +\
             str(stacktrace) +\
             "```\n Please help me generate a fix and include only your fix in your response."
    
    # Generate text using GPT-4
    message=[{"role": "user", "content": prompt}]
    response = client.chat.completions.create(
                model="gpt-4",
                messages=message,
                temperature=0.7,
                max_tokens=max_tokens)
    # Print the generated text
    print(response.choices[0].message)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python GPT_NIO_fixer.py <api_key> <prompt>")
        sys.exit(1)

    prompt_text = sys.argv[2]

    # Optional: Parse additional command-line arguments, such as max_tokens
    max_tokens = 100  # Default value
    if len(sys.argv) >= 4:
        max_tokens = int(sys.argv[3])

    buggy_java_test, stacktrace = read_buggy_java_file_and_stacktrace()
    generate_text(prompt_text, max_tokens, buggy_java_test, stacktrace)
