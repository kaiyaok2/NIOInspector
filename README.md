# NIOInspector (Maven Implementation of NIODebugger)

NIOInspector is a specialized Maven plugin designed to identify and fix non-idempotent-outcome (NIO) flaky tests within Java projects. An NIO flaky test, due to self-polluting shared state, consistently passes in the initial run and fails in subsequent executions within the same environment. Links to opened PRs with respect to NIO tests detected and patched by NIOInspector are collected in this [Google Spreadsheet](https://docs.google.com/spreadsheets/d/1ntSE-rlapzpmoKHGkBs4B110aaLL8Wd0nYj4Br5yQII/edit?usp=sharing).

The `experiments/` folder contains the scripts to run the experiment at scale using NIOInspector.

## What's New in 2.0

- **Modern LLM backend.** The fixer now uses Claude (default: Claude Sonnet 4.5 via AWS Bedrock) instead of the retired GPT-3.5/GPT-4 and local DeepSeek/Qwen models.
- **Whole-file fix generation (Step 5).** With today's long model contexts, the fixer generates a complete, compilable replacement of the entire test file rather than a method-level snippet patch, eliminating the fragile "make the patch compilable" post-processing step.
- **ReACT repair agent (Step 6).** The shell-script-based patch application and reflection loop is replaced by a small ReACT agent (`react_agent.py`) that applies the candidate fix, re-runs the detection phase, and iterates on the execution traces (compiler errors, stack traces, rerun logs) until detection reports success.
- **Newer Java support.** The detector builds and runs on current JDKs (11 through 21+); test-source parsing handles modern Java language syntax.

## Prerequisites

- Java 11 to 21+ and Maven 3.5+ (for detection).
- Python 3.9+ with the `anthropic[bedrock]` package (for test fixing): `pip install "anthropic[bedrock]"`.
- AWS credentials with Amazon Bedrock access to Claude Sonnet 4.5 (`us.anthropic.claude-sonnet-4-5-20250929-v1:0`). Either of:
  - an Amazon Bedrock API key (set `AWS_BEARER_TOKEN_BEDROCK`, or pass the key as the third CLI argument), or
  - standard AWS SigV4 credentials (environment variables, or a profile selected with `NIO_AWS_PROFILE`).

  The Bedrock region is taken from `NIO_BEDROCK_REGION` / `AWS_REGION` (default `us-east-2`).

## Build (Optional)

To build the plugin, run:

    mvn clean install

You can skip building and directly use the [artifacts published to Maven Central](https://central.sonatype.com/artifact/edu.illinois/NIOInspector) following the steps below.

## Detect NIO Flaky Tests

To detect NIO flaky tests in your project, first make sure you have already built your project (or module) beforehand. Then, execute the following command in the root directory of the target project (or module).

    mvn edu.illinois:NIOInspector:rerun

Optional arguments:
- Use `-Dtest=${path.to.testClass#testMethod}` to filter individual test classes or methods.
- Use `-DnumReruns` to configure the number of reruns for each test.

For all tests `${path.to.testClass#testMethod}` reported by NIOInspector, it is recommended to run

    mvn edu.illinois:NIOInspector:rerun -Dtest=${path.to.testClass#testMethod} -DnumReruns=10

to ensure if the reported test is not falsely labelled NIO but flaky due to other reasons, including non-determinism or test order dependency.

The `rerun` task generates a `.NIOInspector` folder in the current directory, containing a folder for each execution timestamp (e.g., `2024-01-01-00-00-01`) with a `rerun-results.log` for debugging purposes.

## Fix NIO Flaky Tests using an LLM Agent (Optional)

### Step 1: Download Fixer

Run the following command to install the Python scripts for fixing (`fixer.py` and `react_agent.py`, bundled with the plugin):

    mvn edu.illinois:NIOInspector:downloadFixer

### Step 2: Collect Test Information

Run the following command to collect information on NIO tests:

    mvn edu.illinois:NIOInspector:collectTestInfo

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a specific run for detection (default uses the most recent rerun).

This command collects a list of potential NIO tests along with their stack traces and relevant source code, stored in `.NIOInspector/{timestamp}/{full_path_test_name}`.

### Step 3: Decide Relevant Source Code

Use the LLM-based agent to determine relevant source code for fixing NIO tests. Run:

    python3 .NIOInspector/fixer.py Sonnet4.5 decide_relevant_source_code

The model argument may be `Sonnet4.5` (the default alias) or any Bedrock Claude model ID / inference profile. An Amazon Bedrock API key may be passed as an optional third argument (otherwise the standard AWS credential chain is used).

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).

### Step 4: Collect Relevant Source Code

Run the following command to gather relevant source code based on the advice from the agent in Step 3:

    mvn edu.illinois:NIOInspector:collectRelevantSourceCode

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a certain run for detection (default uses the most recent rerun).

### Step 5: Generating Fixes for NIO Tests

Use the LLM to generate fixes for detected NIO tests based on gathered information. Run:

    python3 .NIOInspector/fixer.py Sonnet4.5 fix

For each possible NIO test this generates a complete fixed version of the whole test file, stored in `.NIOInspector/{timestamp}/{full_path_test_name}/patch.txt`.

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).
- Use `-max_tokens={num_tokens}` to configure the maximum number of output tokens (default is 60000).
- Use `-extra_prompt={your_prompt}` for additional ad hoc requirements (e.g., "Do not add comments", default is empty string).

### Step 6 (Optional but Recommended): ReACT Agent - Apply, Verify, and Iterate

Users have the option to either apply the generated fix manually (to ensure adherence to coding style, etc.) or let the ReACT agent automate the whole apply-verify-refine loop. From the root directory of the target project (or module), run:

    python3 .NIOInspector/react_agent.py Sonnet4.5

For every test in `possible-NIO-list.txt` the agent applies the candidate fix from Step 5, re-runs the NIOInspector detection phase, and - if the test is still flaky, broken, or does not compile - iterates on the execution traces using filesystem and Maven tools until detection reports success or the budget is exhausted. Fixes are kept only when detection confirms success; otherwise all touched files are restored.

Optional arguments:
- Use `-test=${path.to.testClass#testMethod}` to repair a single test.
- Use `-max_detection_runs={N}` to set the per-test detection budget (default 4).
- Use `-numReruns={N}` reruns per detection (default 3).
- Use `-mvn_timeout={seconds}` timeout per Maven invocation (default 1800).
- Use `-plugin={groupId:artifactId:version}` to pin the plugin coordinates.

Per-test artifacts (verdict, transcript, final fixed files) are stored in `.NIOInspector/{timestamp}/{full_path_test_name}/react_agent_*.json`.
