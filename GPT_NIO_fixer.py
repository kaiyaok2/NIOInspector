import sys
from openai import OpenAI
import os
from datetime import datetime

def get_test_info(cur_test_info_directory):
    reduced_buggy_method_code_path = os.path.join(cur_test_info_directory, "buggyTestMethod")
    if os.path.exists(reduced_buggy_method_code_path):
        with open(reduced_buggy_method_code_path, "r") as file:
            buggy_java_test_contents = file.read()
    max_n = 3  # using a small number to avoid overflowing context window
    stacktrace_contents = []
    for n in range(1, max_n + 1):
        stacktrace_file = os.path.join(cur_test_info_directory, "stacktrace" + str(n))
        if os.path.exists(stacktrace_file):
            with open(stacktrace_file, "r") as file:
                stacktrace_contents.append(file.read())
        else:
            break
    return buggy_java_test_contents, stacktrace_contents


client = OpenAI(api_key=sys.argv[1])

def generate_text(extra_prompt_text, max_tokens, buggy_java_test, stacktrace, cur_test_info_directory):
    stacktrace_text = ""
    for rerun_num in range(len(stacktraces)):
        stacktrace_text += ("Below is the error message in run #" + str(rerun_num + 1) + ":\n```\n" + stacktraces[rerun_num] + "\n```\n")
    prompt = "I need to fix a non-idempotent test that always passes in the first run but fails in all repeated runs in the same JVM. " +\
             "In other words, the test has side effects and “self-pollutes” the state shared among test runs," +\
             "so only the first run succeeds. An example of a non-idempotent test is `void t1() { assertEquals(w, 0); w = 1; }`," +\
             "and a fix is to reset `w` to `0`. Now here's the actual non-idempotent test I need to debug:\n```\n" +\
             str(buggy_java_test) +\
             "```\n" +\
             stacktrace_text +\
             "Please fix this test, and answer with only Java code. Do not include any explanation." +\
             extra_prompt_text
    
    # Generate fix using GPT-4
    message=[{"role": "user", "content": prompt}]
    response = client.chat.completions.create(
                model="gpt-4",
                messages=message,
                temperature=0.7,
                max_tokens=max_tokens)
    # Print the fix to console and write it to patch text file.
    print(response.choices[0].message)
    patch_path = os.path.join(cur_test_info_directory, "patch.txt")
    with open(patch_path, "w") as text_file:
        text_file.write(response.choices[0].message.content.strip())


def find_most_recent_run_logs():
    # Get all the sub-directories containing NIOinspector-generated logs
    current_directory = os.getcwd()
    NIO_inspector_directory = os.path.join(current_directory, ".NIOinspector")
    subdirectories = [d for d in os.listdir(NIO_inspector_directory)\
                      if os.path.isdir(os.path.join(NIO_inspector_directory, d))]

    # Convert directory names to datetime objects
    date_formats = ["%Y-%m-%d-%H-%M-%S"]
    dates = []
    for subdir in subdirectories:
        try:
            date = datetime.strptime(subdir, date_formats[0])
            dates.append((date, subdir))
        except ValueError:
            pass

    # Find the latest subdirectory
    if dates:
        latest_date, latest_subdir = max(dates)
        print("Latest NIOinspector Rerun Timestamp:", latest_date)
        return latest_subdir
    else:
        print("No subdirectories found.")
        return None


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Please specify your API key.")
        sys.exit(1)

    # Optional: Maximum number of tokens; default is 100
    max_tokens = 100
    if len(sys.argv) >= 3:
        max_tokens = int(sys.argv[2])

    # Optional: Additional prompt if ad hoc requiements apply (i.e, "Do not add comments")
    extra_prompt_text = ""
    if len(sys.argv) >= 4:
        extra_prompt_text = sys.argv[3]

    # Optional: A subdirectory w.r.t. a specific previous NIOinspector rerun to debug. Default is the latest.
    subdirectory_name = find_most_recent_run_logs()
    if len(sys.argv) >= 5:
        subdirectory_name = sys.argv[4]

    # Retrieve list of possible NIO lists and debug one by one
    subdirectory = os.path.join(os.getcwd(), ".NIOInspector", subdirectory_name)
    if os.path.exists(subdirectory):
        NIO_list_path = os.path.join(subdirectory, "possible-NIO-list.txt")
        if os.path.exists(NIO_list_path):
            with open(NIO_list_path, "r") as file:
                for line in file:
                    cur_test = line.strip().replace("#", ".")
                    cur_test_info_directory = os.path.join(subdirectory, cur_test)
                    reduced_buggy_test_code, stacktraces = get_test_info(cur_test_info_directory)
                    generate_text(extra_prompt_text, max_tokens, reduced_buggy_test_code, stacktraces, cur_test_info_directory)
        else:
            print("possible-NIO-list.txt does not exist in the subdirectory.")
    else:
        print("Specified subdirectory does not exist or no NIOinspector rerun logs available.")
