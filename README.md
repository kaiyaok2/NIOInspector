# NIOInspector

NIOInspector is a specialized Maven plugin designed to identify and fix non-idempotent-outcome (NIO) flaky tests within Java projects. An NIO flaky test, due to self-polluting shared state, consistently passes in the initial run and fails in subsequent executions within the same environment.

## Prerequisites

- Java 9 to 21 (for detection).
- Maven 3.5+ (for detection).
- Python 3.0+ (for test fixing).

## Build

To build the plugin, run:

    mvn clean install

## Detect NIO Flaky Tests

To detect NIO flaky tests in your project, execute the following command in the root directory of the target project:

    mvn edu.illinois:NIOInspector:rerun

Optional arguments:
- Use `-Dtest=${path.to.testClass#testMethod}` to filter individual test classes or methods.
- Use `-DnumReruns` to configure the number of reruns for each test.

This command generates a `.NIOInspector` folder in the current directory, containing a folder for each execution timestamp (e.g., `2024-01-01-00-00-01`) with a `rerun-results.log` for debugging purposes.

## Fix NIO Flaky Tests

### Step 1: Download Fixer

Run the following command to download the Python script for fixing:

    mvn edu.illinois:NIOInspector:downloadFixer

### Step 2: Collect Test Information

Run the following command to collect information on NIO tests:

    mvn edu.illinois:NIOInspector:collectTestInfo

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a specific run for detection (default uses the most recent rerun).

This command collects a list of potential NIO tests along with their stack traces and relevant source code, stored in `.NIOInspector/{timestamp}/{full_path_test_name}`.

### Step 3: Decide Relevant Source Code

Use GPT-4 to determine relevant source code for fixing NIO tests. Run:

    python3 GPT_NIO_fixer.py decide_relevant_source_code {your_api_key_for_GPT}

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).

### Step 4: Collect Relevant Source Code

Run the following command to gather relevant source code based on the advice from the agent in Step 3:

    mvn edu.illinois:NIOInspector:collectRelevantSourceCode

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a certain run for detection (default uses the most recent rerun).

### Step 5: Fix NIO Tests

Finally, use LLM to fix NIO tests based on gathered information. Run:

    python3 GPT_NIO_fixer.py fix {your_api_key_for_GPT}

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).
- Use `-max_tokens={num_tokens}` to configure the maximum number of tokens in the patch (default is 1000).
- Use `-extra_prompt={your_prompt}` for additional ad hoc requirements (e.g., "Do not add comments", default is empty string).

This command generates a patch for each of the possible NIO test, stored in `.NIOInspector/{timestamp}/{full_path_test_name}/patch.txt`.