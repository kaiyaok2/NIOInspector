"""NIOInspector fixer (step 3 & step 5 of the NIODebugger pipeline).

Uses Claude (default: Claude Sonnet 4.5 on AWS Bedrock) to:
  - mode `decide_relevant_source_code`: decide what extra source code is needed
    to fix a non-idempotent-outcome (NIO) flaky test (consumed by the
    `collectRelevantSourceCode` Maven goal, format unchanged);
  - mode `fix`: generate a complete, compilable replacement of the whole test
    file (modern long-context models make whole-file replacement far more
    reliable than the snippet patches used with legacy models).

Usage (run from the root of the Maven project/module under repair):
    python3 .NIOInspector/fixer.py Sonnet4.5 decide_relevant_source_code
    python3 .NIOInspector/fixer.py Sonnet4.5 fix

Model argument may be `Sonnet4.5` (default alias) or any Bedrock model ID /
inference profile (e.g. `us.anthropic.claude-sonnet-4-5-20250929-v1:0`).

Authentication (first match wins):
  1. Explicit API key argument or AWS_BEARER_TOKEN_BEDROCK env var
     (an Amazon Bedrock API key / bearer token);
  2. AWS SigV4 credentials - the standard AWS credential chain; set
     AWS_PROFILE / NIO_AWS_PROFILE to select a profile.
Region is taken from NIO_BEDROCK_REGION / AWS_REGION (default us-east-2).

Optional flags (position-independent, after the mode):
    -timestamp=YYYY-MM-DD-HH-MM-SS   debug a specific rerun (default: latest)
    -max_tokens=N                    max output tokens (default 60000)
    -extra_prompt=...                ad hoc extra instructions for `fix` mode
"""

import os
import sys
from datetime import datetime

from anthropic import AnthropicBedrock

MODEL_ALIASES = {
    "Sonnet4.5": "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
}
DEFAULT_REGION = "us-east-2"

COMMON_NIO_EXPLANATION = (
    "I have a non-idempotent test that always passes in the first run but fails in all repeated runs "
    "in the same JVM. In other words, the test has side effects and \"self-pollutes\" the state shared "
    "among test runs, so only the first run succeeds. An example of a non-idempotent test is "
    "`void t1() { assertEquals(w, 0); w = 1; }`, and a fix is to reset `w` to `0`. "
    "If global state modification is inherently not cleanable (e.g., you cannot remove a class once "
    "loaded to a classloader), you shall ensure uniqueness for the relevant state. For example, "
    "`ClassGenerator.newInstance(Bean.class.getName())` is non-idempotent because one cannot generate "
    "classes of same names multiple times. But since one cannot remove classes from classloaders, a "
    "possible fix would be `ClassGenerator.newInstance(Bean.class.getName() + UUID.randomUUID().toString())`. "
    "If the polluted state can neither be cleaned nor made unique (e.g., metrics that only accumulate), make the "
    "test robust to pre-existing state instead: capture the relevant baseline values at the start of the test and "
    "assert relative changes (deltas) against that baseline rather than absolute values. "
)


def make_client():
    """Build an AnthropicBedrock client using a Bedrock API key or SigV4 credentials."""
    region = os.environ.get("NIO_BEDROCK_REGION") or os.environ.get("AWS_REGION") or DEFAULT_REGION
    kwargs = {"aws_region": region}
    profile = os.environ.get("NIO_AWS_PROFILE")
    if profile and not os.environ.get("AWS_BEARER_TOKEN_BEDROCK"):
        kwargs["aws_profile"] = profile
    return AnthropicBedrock(**kwargs)


def resolve_model(model_arg):
    return MODEL_ALIASES.get(model_arg, model_arg)


def complete(client, model, prompt, max_tokens):
    """One-shot completion; streams to tolerate long whole-file outputs."""
    with client.messages.stream(
        model=model,
        max_tokens=max_tokens,
        messages=[{"role": "user", "content": prompt}],
    ) as stream:
        message = stream.get_final_message()
    return "".join(block.text for block in message.content if block.type == "text")


def extract_java_code(response_text):
    """Return the content of the first ```java fenced block, else the raw text."""
    start_marker = "```java"
    start = response_text.find(start_marker)
    if start == -1:
        start_marker = "```"
        start = response_text.find(start_marker)
        if start == -1:
            return response_text.strip()
    end = response_text.find("```", start + len(start_marker))
    if end == -1:
        return response_text[start + len(start_marker):].strip()
    return response_text[start + len(start_marker):end].strip()


def find_test_file(class_fqn, search_root=None):
    """Locate the .java source of a test class under the current module.

    `class_fqn` is e.g. com.example.FooTest (no method part). Prefers a path
    matching the package structure; falls back to any file named FooTest.java.
    """
    root = search_root or os.getcwd()
    parts = class_fqn.split(".")
    # Nested classes (com.example.Outer$Inner or Outer.Inner) live in the outer file
    simple = parts[-1].split("$")[0]
    rel_pkg_path = os.path.join(*parts[:-1]) if len(parts) > 1 else ""
    candidates = []
    for dirpath, _, filenames in os.walk(root):
        if os.sep + "target" + os.sep in dirpath + os.sep or os.sep + ".git" in dirpath:
            continue
        for name in filenames:
            if name == simple + ".java":
                candidates.append(os.path.join(dirpath, name))
    if not candidates:
        # The simple name may itself be a nested class: try the enclosing class
        if len(parts) > 1:
            outer_candidates = find_test_file(".".join(parts[:-1]), search_root)
            if outer_candidates:
                return outer_candidates
        return None
    # Prefer paths that contain the package directory structure and a test root
    def score(path):
        s = 0
        if rel_pkg_path and (os.sep + rel_pkg_path + os.sep) in path:
            s += 2
        if os.sep + os.path.join("src", "test") + os.sep in path:
            s += 1
        return -s
    candidates.sort(key=score)
    return candidates[0]


def get_test_info(cur_test_info_directory, mode):
    buggy_java_test_contents = None
    reduced_buggy_method_code_path = os.path.join(cur_test_info_directory, "buggyTestMethod")
    if os.path.exists(reduced_buggy_method_code_path):
        with open(reduced_buggy_method_code_path, "r") as file:
            buggy_java_test_contents = file.read()
    source_code_contents = None
    if mode == "fix":
        source_code_path = os.path.join(cur_test_info_directory, "sourceCode")
        if os.path.exists(source_code_path):
            with open(source_code_path, "r") as file:
                source_code_contents = file.read()
    stacktrace_contents = []
    error_line_contents = []
    max_n = 3  # a small number of reruns is enough signal
    for n in range(1, max_n + 1):
        stacktrace_file = os.path.join(cur_test_info_directory, "stacktrace" + str(n))
        if not os.path.exists(stacktrace_file):
            break
        with open(stacktrace_file, "r") as file:
            stacktrace_contents.append(file.read())
    for n in range(1, max_n + 1):
        error_line_file = os.path.join(cur_test_info_directory, "error_line" + str(n))
        if not os.path.exists(error_line_file):
            break
        with open(error_line_file, "r") as file:
            error_line_contents.append(file.read())
    return buggy_java_test_contents, source_code_contents, stacktrace_contents, error_line_contents


def build_common_prompt(test_name, buggy_java_test, stacktraces, error_lines,
                        prev_test_info_directory):
    prompt = COMMON_NIO_EXPLANATION
    prompt += ("Now here's the actual non-idempotent test `" + test_name + "` that I have:\n```\n" +
               str(buggy_java_test) + "\n```\n")
    # Feedback-based iterative prompting: surface the previous failed attempt, if any
    if prev_test_info_directory:
        prev_patch_path = os.path.join(prev_test_info_directory, "patch.txt")
        prev_agent_response_path = os.path.join(prev_test_info_directory, "agent_response")
        if os.path.isfile(prev_patch_path) and os.path.isfile(prev_agent_response_path):
            with open(prev_agent_response_path, "r", encoding="utf-8") as file:
                prev_agent_response = file.read()
            with open(prev_patch_path, "r", encoding="utf-8") as file:
                prev_patch = file.read()
            prompt += ("In your previous attempt, you suggested: `" + prev_agent_response +
                       "`, but after applying the corresponding patch: \n```\n" + prev_patch +
                       "\n```\n, the test is still non-idempotent.\n")
    for rerun_num, stacktrace in enumerate(stacktraces):
        prompt += ("Below is the error message in run #" + str(rerun_num + 1) + ":\n```\n" +
                   stacktrace + "\n```\n")
        if rerun_num < len(error_lines):
            prompt += ("And the error occurs at this line:\n```\n" + error_lines[rerun_num] + "\n```\n")
    return prompt


def build_decide_prompt(common_prompt):
    return common_prompt + (
        "Based on the knowledge above, please decide: \n"
        "If the test code contains enough information for a fix (i.e., a fix is possible without "
        "'assuming' the existence / functionality of any methods), please just answer `Directly Fixable` "
        "in your response; \n"
        "Otherwise, if you would like to explore the code for one specific custom method / constructor "
        "appearing in the test code, please just answer `Find Method Code: {className.methodName}` "
        "(e.g., `Find Method Code: {MyNIOClass.reset}`) in your response; \n"
        "If you would like to explore the code for a specific custom class relevant to the test code, "
        "please just answer `Find Class Code: {className}` (e.g., `Find Class Code: {MyNIOClass}`) in "
        "your response; \n"
        "If you want to explore all methods with names similar to a hypothesized name in any possibly "
        "relevant classes, please just answer `Find Hypothesized Method: {possibleMethodName}` "
        "(e.g., `Find Hypothesized Method: {resetDataSet}`) in your response; \n"
        "If you generally need source code from more possibly relevant source files before you can make "
        "a decision, please just answer `Find Relevant File`. \n"
        "In general, please just answer one of `Directly Fixable`, `Find Method Code: {className.methodName}`, "
        "`Find Class Code: {className}`, `Find Hypothesized Method: {possibleMethodName}`, or "
        "`Find Relevant File`. Do not include any other text in your response."
    )


def build_fix_prompt(common_prompt, test_name, test_class_fqn, test_file_path,
                     test_file_content, source_code, extra_prompt_text):
    prompt = common_prompt
    if source_code:
        prompt += ("Below is part of the main code relevant to the test class - it may contain methods "
                   "to clean up polluted states:\n```\n" + source_code + "\n```\n")
    prompt += ("Below is the complete current content of the test file `" + test_file_path +
               "` that contains the non-idempotent test:\n```java\n" + test_file_content + "\n```\n")
    prompt += (
        "Please fix the non-idempotent test `" + test_name + "` in the test class `" + test_class_fqn +
        "` and answer with the COMPLETE updated content of this test file. Requirements:\n"
        "- Keep the fix minimal: only change what is needed to make the test idempotent "
        "(e.g., reset shared state, add proper cleanup, or ensure uniqueness of uncleanable global state).\n"
        "- Preserve the test's original intent and assertions; do not weaken or delete the test.\n"
        "- Do not change or remove other tests; include ALL existing code (package declaration, imports, "
        "fields, helpers and all other test methods) unchanged except where the fix requires it.\n"
        "- Add any import statements your fix needs.\n"
        "- The file must be directly compilable as a drop-in replacement of the original file.\n"
        "Answer with ONLY the full Java file content wrapped in a code block starting with ```java and "
        "ending with ```. Do not include any explanation outside the code block. "
        + extra_prompt_text
    )
    return prompt


def find_most_recent_run_timestamps():
    current_directory = os.getcwd()
    nio_inspector_directory = os.path.join(current_directory, ".NIOInspector")
    subdirectories = [
        d for d in os.listdir(nio_inspector_directory)
        if os.path.isdir(os.path.join(nio_inspector_directory, d))
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


def run(model, mode, max_tokens, timestamp, previous_timestamp, extra_prompt_text):
    client = make_client()
    current_run_directory = os.path.join(os.getcwd(), ".NIOInspector", timestamp)
    previous_run_directory = (os.path.join(os.getcwd(), ".NIOInspector", previous_timestamp)
                              if previous_timestamp else None)
    if not os.path.exists(current_run_directory):
        print("Error: Specified current_run_directory does not exist or no NIOInspector rerun logs available.")
        sys.exit(1)
    nio_list_path = os.path.join(current_run_directory, "possible-NIO-list.txt")
    if not os.path.exists(nio_list_path):
        print("Error: possible-NIO-list.txt does not exist in the current_run_directory.")
        sys.exit(1)

    with open(nio_list_path, "r") as file:
        tests = [line.strip() for line in file if line.strip()]

    for raw_test in tests:
        cur_test = raw_test.replace("#", ".")
        test_class_fqn = raw_test.split("#")[0]
        cur_test_info_directory = os.path.join(current_run_directory, cur_test)
        prev_test_info_directory = (os.path.join(previous_run_directory, cur_test)
                                    if previous_run_directory else None)
        buggy_test, source_code, stacktraces, error_lines = get_test_info(cur_test_info_directory, mode)
        test_name = cur_test.split(".")[-1]
        common_prompt = build_common_prompt(test_name, buggy_test, stacktraces, error_lines,
                                            prev_test_info_directory)
        if mode == "decide_relevant_source_code":
            prompt = build_decide_prompt(common_prompt)
            response = complete(client, model, prompt, max_tokens=min(max_tokens, 1000))
            out_path = os.path.join(cur_test_info_directory, "agent_response")
            with open(out_path, "w") as f:
                f.write(response.strip())
            print(cur_test + " -> " + response.strip())
        else:  # fix
            test_file_path = find_test_file(test_class_fqn)
            if test_file_path is None:
                print("Warning: could not locate the source file of " + test_class_fqn +
                      "; falling back to method-snippet fixing.")
                test_file_content = str(buggy_test)
                test_file_path = "(unknown - emit the full test class)"
            else:
                with open(test_file_path, "r", encoding="utf-8", errors="replace") as f:
                    test_file_content = f.read()
                # Remember where the fix should be applied (used by react_agent.py)
                with open(os.path.join(cur_test_info_directory, "testFilePath"), "w") as f:
                    f.write(test_file_path)
            prompt = build_fix_prompt(common_prompt, test_name, test_class_fqn, test_file_path,
                                      test_file_content, source_code, extra_prompt_text)
            response = complete(client, model, prompt, max_tokens=max_tokens)
            fixed_file = extract_java_code(response)
            with open(os.path.join(cur_test_info_directory, "patch.txt"), "w") as f:
                f.write(fixed_file)
            with open(os.path.join(cur_test_info_directory, "agent_response"), "w") as f:
                f.write(response.strip())
            print("Generated whole-file fix for " + cur_test + " (" + str(len(fixed_file)) + " chars)")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 fixer.py <model> <decide_relevant_source_code|fix> "
              "[api_key] [-timestamp=...] [-max_tokens=N] [-extra_prompt=...]")
        sys.exit(1)

    model = resolve_model(sys.argv[1])
    mode = sys.argv[2]
    if mode not in ("decide_relevant_source_code", "fix"):
        print("Error: Invalid mode. Use either 'decide_relevant_source_code' or 'fix'.")
        sys.exit(1)

    max_tokens = 60000
    timestamp, previous_timestamp = find_most_recent_run_timestamps()
    extra_prompt_text = ""
    for arg in sys.argv[3:]:
        if arg.startswith("-max_tokens="):
            max_tokens = int(arg.split("=", 1)[1])
        elif arg.startswith("-timestamp="):
            timestamp = arg.split("=", 1)[1]
        elif arg.startswith("-extra_prompt="):
            extra_prompt_text = arg.split("=", 1)[1]
        elif not arg.startswith("-"):
            # Positional API key (an Amazon Bedrock API key / bearer token)
            os.environ["AWS_BEARER_TOKEN_BEDROCK"] = arg

    if timestamp is None:
        print("Error: no NIOInspector rerun logs available.")
        sys.exit(1)

    run(model, mode, max_tokens, timestamp, previous_timestamp, extra_prompt_text)
