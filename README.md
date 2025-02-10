# NIOInspector (Maven Implementation of NIODebugger)

NIOInspector is a specialized Maven plugin designed to identify and fix non-idempotent-outcome (NIO) flaky tests within Java projects. An NIO flaky test, due to self-polluting shared state, consistently passes in the initial run and fails in subsequent executions within the same environment. Links to opened PRs with respect to NIO tests detected and patched by NIOInspector are collected in this [Google Spreadsheet](https://docs.google.com/spreadsheets/d/1ntSE-rlapzpmoKHGkBs4B110aaLL8Wd0nYj4Br5yQII/edit?usp=sharing).

The `experiments/` folder contains the scripts to run the experiment at scale using NIOInspector.

## Prerequisites

- Java 9 to 21 (for detection).
- Maven 3.5+ (for detection).
- Python 3.0+ (for test fixing).
- Required Python Packages: openai (for GPT-based test fixing, recommended); transformers & torch (for DeepSeek Coder-based or Qwen Coder-based test fixing). GPU access is recommended for open-source models for efficiency.

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

Run the following command to download the Python scripts for fixing:

    mvn edu.illinois:NIOInspector:downloadFixer

### Step 2: Collect Test Information

Run the following command to collect information on NIO tests:

    mvn edu.illinois:NIOInspector:collectTestInfo

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a specific run for detection (default uses the most recent rerun).

This command collects a list of potential NIO tests along with their stack traces and relevant source code, stored in `.NIOInspector/{timestamp}/{full_path_test_name}`.

### Step 3: Decide Relevant Source Code

Use the LLM-based agent to determine relevant source code for fixing NIO tests. Run:

    python3 .NIOInspector/fixer.py {model} decide_relevant_source_code {your_api_key_for_GPT}

Notice that `{model}` can be one of `GPT4`, `GPT3.5`, `Qwen`, or `DeepSeek`. If you use non-gpt models, `{your_api_key_for_GPT}` is not needed. Notice that when you specify `DeepSeek`, `deepseek-coder-33b-instruct` is used; when you specify `Qwen`, `Qwen2.5-Coder-32B-Instruct` is used. We have only tested NIOInspector under the LLMs mentioned above, but users can modify `.NIOInspector/fixer.py` to make use of other LLMs.

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).

### Step 4: Collect Relevant Source Code

Run the following command to gather relevant source code based on the advice from the agent in Step 3:

    mvn edu.illinois:NIOInspector:collectRelevantSourceCode

Optional arguments:
- Use `-logFile=${path.to.most.recent.log}` to specify a certain run for detection (default uses the most recent rerun).

### Step 5: Generating patches for NIO Tests

Finally, use an LLM to generate fixes for detected NIO tests based on gathered information. Run:

    python3 .NIOInspector/fixer.py {model} fix {your_api_key_for_GPT}

Similarly, `{model}` can be one of `GPT4`, `GPT3.5`, `Qwen`, or `DeepSeek`. If you use non-gpt models, `{your_api_key_for_GPT}` is not needed.

Optional arguments:
- Use `-timestamp=${xxxx-xx-xx-xx-xx-xx}` to specify a certain run for detection (default uses the most recent rerun).
- Use `-max_tokens={num_tokens}` to configure the maximum number of tokens in the patch (default is 1000).
- Use `-extra_prompt={your_prompt}` for additional ad hoc requirements (e.g., "Do not add comments", default is empty string).

This command generates a patch for each of the possible NIO test, stored in `.NIOInspector/{timestamp}/{full_path_test_name}/patch.txt`.

### Step 6 (Optional): Applying Patches & Reflection with Feedback-Based Iterative Prompting

Users have the option to either apply the patch manually (to ensure adherence to coding style, etc.) or automate the process using the LLM. To apply the patch automatically, use the following command:

```bash
    cd .NIOInspector/{timestamp}/{full_path_test_name}
    ../../../.NIOInspector/apply_patch.sh ../../.. {model} {your_api_key_for_GPT}
```
Similarly, `{model}` can be one of `GPT4`, `GPT3.5`, `Qwen`, or `DeepSeek`. If you use non-gpt models, `{your_api_key_for_GPT}` is not needed.

Due to the high cost of using GPT-4, reflection with feedback-based iterative prompting is not a mandatory step in the patch generation pipeline. However, if the test flakiness is not resolved after applying the patch and rebuilding the project, users can simply rerun the detection phase and repeat steps 2-5 above. NIOInspector will re-enter the fixer phase with the prompt automatically enhanced by the (most recent) previous relevant source code selection, patch, and execution results.

If you're aware of the cost of the LLMs and still want to fully automate the reflection process, you can use the `experiments/run_plugin_at_scale.sh` script, which allow up to three iterations for each test.
