import sys
from openai import OpenAI
from transformers import AutoTokenizer, AutoModelForCausalLM
import torch
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


def generate_text(extra_prompt_text, max_tokens, buggy_java_test, source_code, stacktraces,
                  error_lines, cur_test_info_directory, prev_test_info_directory, test_name, mode, model, client):
    
    prev_patch = None
    prev_agent_response = None
    prev_patch_path = "inexistent"
    prev_agent_response_path = "inexistent"
    if prev_test_info_directory:
        prev_patch_path = os.path.join(prev_test_info_directory, "patch.txt")
        prev_agent_response_path = os.path.join(prev_test_info_directory, "agent_response")
    if os.path.exists(prev_patch_path) and os.path.exists(prev_agent_response_path):
        if os.path.isfile(prev_patch_path) and os.path.isfile(prev_agent_response_path):
            with open(prev_patch_path, 'r', encoding='utf-8') as file:
                prev_patch = file.read()
            with open(prev_agent_response_path, 'r', encoding='utf-8') as file:
                prev_agent_response = file.read()
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
        "```\n"
    if prev_patch and prev_agent_response:
        common_prompt += ("In your previous attempt, you suggested: `" +\
            prev_agent_response +\
            "`, but after applying the corresponding patch: \n```\n" +\
            prev_patch +\
            "\n```\n, the test is still non-idempotent."
        )
    common_prompt += stacktrace_text
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
            "`, and answer with only Java code of the fixed test. Do not include any explanation. Make sure you add import statements when needed." +\
            extra_prompt_text
    
    if mode == 'fix':
        file_to_write = os.path.join(cur_test_info_directory, "patch.txt")
    else:
        file_to_write = os.path.join(cur_test_info_directory, "agent_response")
    
    if model.startswith('GPT'):
        GPTModel = "gpt-4"
        if model == 'GPT3.5':
            GPTModel = "gpt-3.5-turbo"
        # Generate fix using GPT4
        message=[{"role": "user", "content": prompt}]
        response = client.chat.completions.create(
                    model=GPTModel,
                    messages=message,
                    temperature=0.7,
                    max_tokens=max_tokens)
        # Print the fix/agent response to console and write it to patch text file.
        print(response.choices[0].message)
        with open(file_to_write, "w") as text_file:
            text_file.write(response.choices[0].message.content.strip())
    elif model == 'DeepSeek':
        # Generate fix using DeepSeek Coder
        tokenizer = AutoTokenizer.from_pretrained("deepseek-ai/deepseek-coder-33b-instruct", trust_remote_code=True)
        model = AutoModelForCausalLM.from_pretrained("deepseek-ai/deepseek-coder-33b-instruct", trust_remote_code=True, torch_dtype=torch.bfloat16).cuda()
        messages = [
            { 'role': 'user', 'content': prompt}
        ]
        inputs = tokenizer.apply_chat_template(messages, add_generation_prompt=True, return_tensors="pt").to(model.device)
        outputs = model.generate(inputs, max_new_tokens=max_tokens, do_sample=True, temperature=0.5, num_return_sequences=1, eos_token_id=tokenizer.eos_token_id)
        response = tokenizer.decode(outputs[0][len(inputs[0]):], skip_special_tokens=True)
        # Print the fix/agent response to console and write it to patch text file.
        print(response)
        with open(file_to_write, "w") as text_file:
            text_file.write(response.strip())
    elif model == 'Qwen':
        # Generate fix using Qwen2.5-Coder
        model = AutoModelForCausalLM.from_pretrained(
            "Qwen/Qwen2.5-Coder-32B-Instruct",
            torch_dtype="auto",
            device_map="auto"
        ).cuda()
        tokenizer = AutoTokenizer.from_pretrained("Qwen/Qwen2.5-Coder-32B-Instruct")
        messages = [
            {"role": "system", "content": "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."},
            {"role": "user", "content": prompt}
        ]
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True
        )
        model_inputs = tokenizer([text], return_tensors="pt").to(model.device)
        generated_ids = model.generate(
            **model_inputs,
            max_new_tokens=1024,
            do_sample=True,
            temperature=0.6
        )
        generated_ids = [
            output_ids[len(input_ids):] for input_ids, output_ids in zip(model_inputs.input_ids, generated_ids)
        ]
        response = tokenizer.batch_decode(generated_ids, skip_special_tokens=True)[0]
        # Print the fix/agent response to console and write it to patch text file.
        print(response)
        with open(file_to_write, "w") as text_file:
            text_file.write(response.strip())
    else:
        raise Exception("Unsupported Model.")


def find_most_recent_run_timestamps():
    current_directory = os.getcwd()
    NIO_inspector_directory = os.path.join(current_directory, ".NIOInspector")
    subdirectories = [
        d for d in os.listdir(NIO_inspector_directory)
        if os.path.isdir(os.path.join(NIO_inspector_directory, d))
    ]

    date_format = "%Y-%m-%d-%H-%M-%S"
    dates = []
    for subdir in subdirectories:
        try:
            date = datetime.strptime(subdir, date_format)
            dates.append((date, subdir))
        except ValueError:
            pass

    dates.sort(reverse=True, key=lambda x: x[0])
    most_recent = dates[0][1] if dates else None
    second_most_recent = dates[1][1] if len(dates) > 1 else None

    if most_recent:
        print("Latest NIOInspector Rerun Timestamp:", dates[0][0])
    else:
        print("No subdirectories found.")

    return [most_recent, second_most_recent]


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Error: Missing parameters. Please specify the model and the mode(explore or fix)")
        sys.exit(1)

    model = sys.argv[1]
    client = None
    if model == 'GPT4' or model == 'GPT3.5':
        if len(sys.argv) < 4:
            print("Error: Missing parameters. Please specify the mode(explore or fix) and your API key")
            sys.exit(1)
        client = OpenAI(api_key=sys.argv[3])
    elif model == 'DeepSeek' or model == 'Qwen':
        if len(sys.argv) < 3:
            print("Error: Missing Parameters. Please specify the mode(explore or fix)")
            sys.exit(1)
    else:
        print("Error: Unsupported Model")
        sys.exit(1)
    
    mode = sys.argv[2]
    if mode != 'decide_relevant_source_code' and mode != 'fix':
        print("Error: Invalid mode. Use either 'decide_relevant_source_code' or 'fix'.")
        sys.exit(1)
    
    # Configurable maximum number of tokens; default is 1000
    max_tokens = 1000

    # Configurable timestamp w.r.t. a specific previous NIOInspector rerun to debug. Default is the latest.
    timestamp, previous_timestamp = find_most_recent_run_timestamps()

    # Configurable additional prompt if ad hoc requiements apply (i.e, "Do not add comments"). Default is empty string.
    extra_prompt_text = ""

    
    if len(sys.argv) >= 3 + (model == 'GPT4' or model == 'GPT3.5'):
        for arg in sys.argv[3 + (model == 'GPT4' or model == 'GPT3.5'):]:
            if arg.startswith('-max_tokens='):
                max_tokens = int(arg.split('=')[1])
            elif arg.startswith('-timestamp='):
                timestamp = arg.split('=')[1]
            elif arg.startswith('-extra_prompt='):
                extra_prompt_text = arg.split('=')[1]


    # Retrieve list of possible NIO lists and debug one by one
    current_run_directory = os.path.join(os.getcwd(), ".NIOInspector", timestamp)
    previous_run_directory = os.path.join(os.getcwd(), ".NIOInspector", previous_timestamp) if previous_timestamp else None

    if os.path.exists(current_run_directory):
        NIO_list_path = os.path.join(current_run_directory, "possible-NIO-list.txt")
        if os.path.exists(NIO_list_path):
            with open(NIO_list_path, "r") as file:
                for line in file:
                    cur_test = line.strip().replace("#", ".")
                    cur_test_info_directory = os.path.join(current_run_directory, cur_test)
                    prev_test_info_directory = os.path.join(previous_run_directory, cur_test) if previous_run_directory else None
                    reduced_buggy_test_code, source_code, stacktraces, error_lines = get_test_info(cur_test_info_directory, mode)
                    generate_text(extra_prompt_text, max_tokens, reduced_buggy_test_code, source_code,
                                  stacktraces, error_lines, cur_test_info_directory, prev_test_info_directory, cur_test.split(".")[-1], mode, model, client)
        else:
            print("Error: possible-NIO-list.txt does not exist in the current_run_directory.")
    else:
        print("Error: Specified current_run_directory does not exist or no NIOInspector rerun logs available.")
