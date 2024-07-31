import sys
from openai import OpenAI
import os
from datetime import datetime

def get_test_info(cur_test_info_directory, mode):
    reduced_buggy_method_code_path = os.path.join(cur_test_info_directory, "buggyTestMethod")
    if os.path.exists(reduced_buggy_method_code_path):
        with open(reduced_buggy_method_code_path, "r") as file:
            buggy_java_test_contents = file.read()
    if mode == 'fix':
        source_code_path = os.path.join(cur_test_info_directory, "sourceCode")
        if os.path.exists(source_code_path):
            with open(source_code_path, "r") as file:
                source_code_contents = file.read()
        else:
            source_code_contents = None
    else:
        source_code_contents = ""
    max_n = 3  # using a small number to avoid overflowing context window
    stacktrace_contents = []
    for n in range(1, max_n + 1):
        stacktrace_file = os.path.join(cur_test_info_directory, "stacktrace" + str(n))
        if os.path.exists(stacktrace_file):
            with open(stacktrace_file, "r") as file:
                stacktrace_contents.append(file.read())
        else:
            break
    error_line_contents = []
    for n in range(1, max_n + 1):
        error_line_file = os.path.join(cur_test_info_directory, "error_line" + str(n))
        if os.path.exists(error_line_file):
            with open(error_line_file, "r") as file:
                error_line_contents.append(file.read())
        else:
            break
    return buggy_java_test_contents, source_code_contents, stacktrace_contents, error_line_contents


client = OpenAI(api_key=sys.argv[2])

def generate_text(extra_prompt_text, max_tokens, buggy_java_test, source_code, stacktraces, error_lines, cur_test_info_directory, test_name, mode):
    stacktrace_text = ""
    for rerun_num in range(len(stacktraces)):
        stacktrace_text += ("Below is the error message in run #" + str(rerun_num + 1) + ":\n```\n" + stacktraces[rerun_num] + "\n```\n")
        stacktrace_text += ("And the error occurs at this line: " + ":\n```\n" + error_lines[rerun_num] + "\n```\n")
    common_prompt = "I have a non-idempotent test that always passes in the first run but fails in all repeated runs in the same JVM. " +\
        "In other words, the test has side effects and “self-pollutes” the state shared among test runs," +\
        "so only the first run succeeds. An example of a non-idempotent test is `void t1() { assertEquals(w, 0); w = 1; }`," +\
        "and a fix is to reset `w` to `0`. Now here's the actual non-idempotent test `" +\
        test_name +\
        "` that I have:\n```\n" +\
        str(buggy_java_test) +\
        "```\n" +\
        stacktrace_text
    if mode == 'decide_relevant_source_code':
        prompt = common_prompt +\
            "Based on the knowledge above, please decide: \n" +\
            "If the test code contains enough information for a fix (i.e., a fix is possible without 'assuming' the existence / functionality of any methods)" +\
            ", please just answer `Directly Fixable` in your response; \n" +\
            "Otherwise, if you would like to explore the code for one specific custom method / constructor appearing in the test code, " +\
            ", please just answer `Find Method Code: {className.methodName}` (e.g., `Find Method Code: {MyNIOClass.reset}`) in your response; \n" +\
            "If you would like to explore the code for a specific custom class relevant to the test code, " +\
            ", please just answer `Find Class Code: {className}` (e.g., `Find Class Code: {MyNIOClass}`) in your response; \n" +\
            "If want to explore all methods with names similar to a hypothesized name in any possibly relevant classes" +\
            ", please just answer `Find Hypothesized Method: {possibleMethodName}` (e.g., `Find Hypothesized Method: {resetDataSet}`) in your response; \n" +\
            "If you generally need source code from more possibly relevant source files before you can make a decision, please just answer `Find Relevant File`. \n" +\
            "In general, please just answer one of `Directly Fixable`, `Find Method Code: {className.methodName}`, `Find Class Code: {className}`," +\
            "`Find Hypothesized Method: {possibleMethodName}`, or `Find Relevant File`. Do not include any other text in your response."
    else:
        if source_code is not None:
            source_code_proving_prompt = "Below is part of the main code relevant to the test class - it may contain methods to clean up polluted states:```\n" +\
                source_code +\
                "\n```\n"
        else:
            source_code_proving_prompt = "Use all information above - "
        prompt = common_prompt +\
            source_code_proving_prompt +\
            "Please directly fix the non-idempotent test `" +\
            test_name +\
            "`, and answer with only Java code of the fixed test. Do not include any explanation." +\
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
    if mode == 'fix':
        patch_path = os.path.join(cur_test_info_directory, "patch.txt")
    else:
        patch_path = os.path.join(cur_test_info_directory, "agent_response")
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
    if len(sys.argv) < 3:
        print("Error: Invalid Input. Please specify your API key and the mode(explore or fix).")
        sys.exit(1)

    mode = sys.argv[1]
    if mode != 'decide_relevant_source_code' and mode != 'fix':
        print("Error: Invalid mode. Use either 'decide_relevant_source_code' or 'fix'.")
        sys.exit(1)
    
    # Configurable maximum number of tokens; default is 1000
    max_tokens = 1000

    # Configurable timestamp w.r.t. a specific previous NIOinspector rerun to debug. Default is the latest.
    timestamp = find_most_recent_run_logs()

    # Configurable additional prompt if ad hoc requiements apply (i.e, "Do not add comments"). Default is empty string.
    extra_prompt_text = ""

    
    if len(sys.argv) >= 4:
        for arg in sys.argv[3:]:
            if arg.startswith('-max_tokens='):
                max_tokens = int(arg.split('=')[1])
            elif arg.startswith('-timestamp='):
                timestamp = arg.split('=')[1]
            elif arg.startswith('-extra_prompt='):
                extra_prompt_text = arg.split('=')[1]


    # Retrieve list of possible NIO lists and debug one by one
    subdirectory = os.path.join(os.getcwd(), ".NIOInspector", timestamp)
    if os.path.exists(subdirectory):
        NIO_list_path = os.path.join(subdirectory, "possible-NIO-list.txt")
        if os.path.exists(NIO_list_path):
            with open(NIO_list_path, "r") as file:
                for line in file:
                    cur_test = line.strip().replace("#", ".")
                    cur_test_info_directory = os.path.join(subdirectory, cur_test)
                    reduced_buggy_test_code, source_code, stacktraces, error_lines = get_test_info(cur_test_info_directory, mode)
                    generate_text(extra_prompt_text, max_tokens, reduced_buggy_test_code, source_code,
                                  stacktraces, error_lines, cur_test_info_directory, cur_test.split(".")[-1], mode)
        else:
            print("Error: possible-NIO-list.txt does not exist in the subdirectory.")
    else:
        print("Error: Specified subdirectory does not exist or no NIOinspector rerun logs available.")
