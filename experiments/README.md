# Evaluation Scripts and Results

This folder contains the scripts and results of evaluating NIOInspector at scale.

## Core Contents

- `run_plugin_at_scale.sh`: A script that automatically runs the plugin on the projects listed in `projects.txt`. By default, the script goes through all phases with the selected LLM, using 3 runs of iterative-prompting based reflection with the addition of unsuccessful previous patches and execution results. During execution, this script calls `run_plugin_on_project.sh` for each project, which uses `apply_nios.sh`, `apply_patch.sh`, and `generate_compilable_patch.py` to apply the NIODebugger-generated patch.
- `collect_NIO_information.sh`: A script that collects the detected NIO tests into a LaTex table.

## Usage

1. **Run the Plugin at Scale**:
   - Generate a `projects.txt`, where each line contains a project slug you want to run NIOInspector on (e.g. `alibaba/COLA`).
   - To run the plugin on all projects listed in `projects.txt`, execute the following command in a Linux environment:
     ```sh
     ./run_plugin_at_scale.sh projects.txt {model} {your_api_key_for_GPT}
     ```
   `{model}` can be one of `GPT4`, `GPT3.5`, `Qwen`, or `DeepSeek`. If you use non-gpt models (`Qwen` or `DeepSeek`), `{your_api_key_for_GPT}` is not needed.

2. **Collect NIO Information**:
   - After running the plugin, collect the relevant logs by executing:
     ```sh
     ./collect_NIO_information.sh
     ```

3. **Results**:
   - The `result.csv` file contains the general detection results for all projects.
   - The `NIO_flaky_tests.csv` file lists all possible NIO tests detected.
   - The patches are automatically applied. You can also examine each `.NIOInspector/{timestamp}/{full_path_test_name}/patch.txt` where the LLM fails to generate a correct `.diff` file for.

## Notes

- Ensure that all scripts have execute permissions. You can set the permissions using:
  ```sh
  chmod +x *.sh
  ```