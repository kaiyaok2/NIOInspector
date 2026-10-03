# Evaluation Scripts and Results

This folder contains the scripts and results of evaluating NIOInspector at scale.

## Core Contents

- `run_plugin_at_scale.sh`: A script that automatically runs the plugin on the projects listed in `projects.txt`. By default, the script goes through all phases with the selected LLM. During execution, this script calls `run_plugin_on_project.sh` for each project; patch application, verification, and feedback-based reflection are handled by the bundled ReACT agent (`react_agent.py`), which iterates on execution traces until the detection phase reports success.
- `collect_NIO_information.sh`: A script that collects the detected NIO tests into a LaTex table.

## Usage

1. **Run the Plugin at Scale**:
   - Generate a `projects.txt`, where each line contains a project slug you want to run NIOInspector on (e.g. `alibaba/COLA`).
   - To run the plugin on all projects listed in `projects.txt`, execute the following command in a Linux environment:
     ```sh
     ./run_plugin_at_scale.sh projects.txt {model} {bedrock_api_key}
     ```
   `{model}` defaults to `Sonnet4.5` (Claude Sonnet 4.5 on AWS Bedrock); any Bedrock Claude model ID / inference profile may be given instead. `{bedrock_api_key}` is an optional Amazon Bedrock API key - without it, the standard AWS credential chain (or the profile in `$NIO_AWS_PROFILE`) is used.

2. **Collect NIO Information**:
   - After running the plugin, collect the relevant logs by executing:
     ```sh
     ./collect_NIO_information.sh
     ```

3. **Results**:
   - The `result.csv` file contains the general detection results for all projects.
   - The `NIO_flaky_tests.csv` file lists all possible NIO tests detected.
   - Verified fixes are kept in the working tree (unverified ones are rolled back). Per-test artifacts - the candidate whole-file fix (`patch.txt`), the agent verdict (`react_agent_result.json`), and the full tool-use transcript (`react_agent_transcript.json`) - are stored under `.NIOInspector/{timestamp}/{full_path_test_name}/`.

## Notes

- Ensure that all scripts have execute permissions. You can set the permissions using:
  ```sh
  chmod +x *.sh
  ```