"""NIOInspector ReACT repair agent (step 6 of the NIODebugger pipeline).

Replaces the legacy `apply_patch.sh` + `generate_compilable_patch.py` scripts
with a small ReACT (Reason + Act) agent. For every NIO test reported by the
detection phase, the agent:

  1. starts from the whole-file fix produced by `fixer.py fix` (if present),
  2. applies it to the working tree,
  3. re-runs the NIOInspector detection phase for that test, and
  4. iterates on the execution traces (compiler errors, stack traces, rerun
     logs) until detection reports success or the iteration budget is spent.

The agent drives Claude (default: Claude Sonnet 4.5 on AWS Bedrock) through a
plain tool-use loop with filesystem + Maven tools. If a test cannot be fixed
within budget, every file the agent touched for that test is restored.

Usage (run from the root of the Maven project/module under repair):
    python3 .NIOInspector/react_agent.py Sonnet4.5

Optional flags:
    -test=pkg.Class#method   repair a single test (default: every test in
                             possible-NIO-list.txt)
    -timestamp=...           use a specific detection run (default: latest)
    -max_detection_runs=N    detection budget per test (default 4)
    -max_turns=N             model-call budget per test (default 40)
    -numReruns=N             reruns per detection (default 3)
    -mvn_timeout=SECONDS     timeout per Maven invocation (default 1800)
    -plugin=G:A[:V]          NIOInspector plugin coordinates
                             (default edu.illinois:NIOInspector:2.0.0)

Authentication and region resolution are identical to fixer.py.
"""

import difflib
import json
import os
import shlex
import subprocess
import sys
from datetime import datetime

from anthropic import AnthropicBedrock

MODEL_ALIASES = {
    "Sonnet4.5": "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
}
DEFAULT_REGION = "us-east-2"
DEFAULT_PLUGIN = "edu.illinois:NIOInspector:2.0.0"

TOOL_RESULT_LIMIT = 8000  # chars per tool result handed back to the model

SYSTEM_PROMPT = """You are an expert Java test engineer repairing a non-idempotent-outcome (NIO) flaky test.

An NIO test passes on its first run but fails on repeated runs in the same JVM, because it "self-pollutes" shared state (static fields, singletons, files, databases, registered resources, generated classes, ...). A correct fix makes the test pass on EVERY run in the same JVM, typically by:
- resetting / cleaning up the polluted shared state (preferably in the test or an @After/@AfterEach/@Before/@BeforeEach hook), or
- when the state is inherently uncleanable (e.g. classes cannot be unloaded from a classloader), ensuring uniqueness (e.g. appending a UUID to a generated-class name), or
- when the state can neither be cleaned nor made unique (e.g. metrics/counters that only accumulate), making the test robust to pre-existing state: capture the relevant baseline values at the start of the test and assert relative changes (deltas) against that baseline instead of absolute values.

Hard requirements:
- Keep the fix minimal and local to test code; never modify main (non-test) source code.
- Preserve the test's intent and assertions; do not weaken, skip, or delete the test.
- Do not break other tests in the file.

You are given execution traces of the failing reruns and (usually) a candidate fixed version of the test file. Work step by step:
1. Apply the candidate fix with write_file (or write your own improved fix). For large files, prefer edit_file with a small unique snippet over rewriting the whole file.
2. Call run_detection to compile and re-run the NIO detection for the test.
3. Read the verdict and traces. If compilation failed or the test is still flaky, reason about the root cause - use read_file / search_code to inspect any file in the project - and refine the fix.
4. Repeat until run_detection reports SUCCESS. Only stop earlier if the budget is exhausted.

Never claim success without a SUCCESS verdict from run_detection in this conversation. When done, summarize the root cause of the flakiness and the final fix in a short paragraph."""

TOOLS = [
    {
        "name": "read_file",
        "description": "Read a text file from the project. Returns the file content (possibly truncated in the middle if very large).",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path relative to the project root (or absolute within it)."},
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Overwrite (or create) a text file in the project with the given content. The previous version is backed up automatically.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path relative to the project root (or absolute within it)."},
                "content": {"type": "string", "description": "The complete new file content."},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace an exact text snippet in a file with new text. Use this for targeted edits in large files instead of rewriting the whole file. The old_text must appear exactly once in the file. The previous version is backed up automatically.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path relative to the project root (or absolute within it)."},
                "old_text": {"type": "string", "description": "The exact existing text to replace (must be unique in the file)."},
                "new_text": {"type": "string", "description": "The replacement text."},
            },
            "required": ["path", "old_text", "new_text"],
        },
    },
    {
        "name": "search_code",
        "description": "Search the project source tree for a regex pattern (grep -rn). Returns matching lines with file paths and line numbers.",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Extended regex to search for."},
                "path": {"type": "string", "description": "Subdirectory to search (default: project root)."},
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "list_dir",
        "description": "List the entries of a directory in the project.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path relative to the project root (default: project root)."},
            },
        },
    },
    {
        "name": "run_detection",
        "description": "Compile the tests (mvn test-compile) and re-run the NIOInspector detection phase for the test under repair. Returns a SUCCESS / STILL_FLAKY / BROKEN_TEST / COMPILE_ERROR verdict plus the relevant build or rerun traces.",
        "input_schema": {"type": "object", "properties": {}},
    },
]


def make_client():
    region = os.environ.get("NIO_BEDROCK_REGION") or os.environ.get("AWS_REGION") or DEFAULT_REGION
    kwargs = {"aws_region": region}
    profile = os.environ.get("NIO_AWS_PROFILE")
    if profile and not os.environ.get("AWS_BEARER_TOKEN_BEDROCK"):
        kwargs["aws_profile"] = profile
    return AnthropicBedrock(**kwargs)


def truncate(text, limit=TOOL_RESULT_LIMIT):
    if len(text) <= limit:
        return text
    half = limit // 2
    return text[:half] + "\n... [" + str(len(text) - limit) + " chars truncated] ...\n" + text[-half:]


def find_most_recent_run_timestamp(root):
    nio_dir = os.path.join(root, ".NIOInspector")
    dates = []
    for subdir in os.listdir(nio_dir):
        try:
            dates.append((datetime.strptime(subdir, "%Y-%m-%d-%H-%M-%S"), subdir))
        except ValueError:
            pass
    dates.sort(reverse=True)
    return dates[0][1] if dates else None


class RepairSession:
    """ReACT loop fixing one NIO test."""

    def __init__(self, client, model, project_root, test_id, info_dir, config):
        self.client = client
        self.model = model
        self.root = os.path.realpath(project_root)
        self.test_id = test_id  # pkg.Class#method
        self.info_dir = info_dir
        self.config = config
        self.backups = {}  # abs path -> original content (None = file did not exist)
        self.detection_runs = 0
        self.last_verdict = None
        self.transcript = []

    # ---------- tools ----------

    def _resolve(self, path):
        abs_path = os.path.realpath(os.path.join(self.root, path))
        if not (abs_path == self.root or abs_path.startswith(self.root + os.sep)):
            raise ValueError("Path escapes the project root: " + path)
        return abs_path

    def tool_read_file(self, path):
        abs_path = self._resolve(path)
        with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
            return truncate(f.read(), 48000)

    def tool_write_file(self, path, content):
        abs_path = self._resolve(path)
        if abs_path not in self.backups:
            if os.path.exists(abs_path):
                with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                    self.backups[abs_path] = f.read()
            else:
                self.backups[abs_path] = None
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)
        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(content)
        return "Wrote " + str(len(content)) + " chars to " + os.path.relpath(abs_path, self.root)

    def tool_edit_file(self, path, old_text, new_text):
        abs_path = self._resolve(path)
        with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        count = content.count(old_text)
        if count == 0:
            return "TOOL ERROR: old_text not found in " + path + " (check exact whitespace)."
        if count > 1:
            return "TOOL ERROR: old_text appears " + str(count) + " times in " + path + "; provide a longer unique snippet."
        if abs_path not in self.backups:
            self.backups[abs_path] = content
        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(content.replace(old_text, new_text, 1))
        return "Edited " + os.path.relpath(abs_path, self.root)

    def tool_search_code(self, pattern, path=None):
        abs_path = self._resolve(path or ".")
        cmd = ["grep", "-rnE", "--include=*.java", "--include=*.xml", "--include=*.properties",
               "--exclude-dir=target", "--exclude-dir=.git", pattern, abs_path]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        out = proc.stdout.replace(self.root + os.sep, "")
        return truncate(out) if out else "(no matches)"

    def tool_list_dir(self, path=None):
        abs_path = self._resolve(path or ".")
        return truncate("\n".join(sorted(os.listdir(abs_path))), 4000)

    def tool_run_detection(self):
        self.detection_runs += 1
        mvn = self.config.get("mvn", "mvn")
        extra = shlex.split(os.environ.get("NIO_MVN_ARGS", ""))
        timeout = self.config["mvn_timeout"]

        # process-test-classes (not just test-compile) so bytecode post-processing
        # bound to that phase (e.g. ebean entity enhancement) also runs
        compile_cmd = [mvn, "-B", "process-test-classes"] + extra
        proc = subprocess.run(compile_cmd, capture_output=True, text=True,
                              timeout=timeout, cwd=self.root)
        if proc.returncode != 0:
            tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-120:])
            self.last_verdict = "COMPILE_ERROR"
            return "VERDICT: COMPILE_ERROR\nThe fix does not compile. Build output tail:\n" + truncate(tail)

        rerun_cmd = [mvn, "-B", self.config["plugin"] + ":rerun",
                     "-Dtest=" + self.test_id,
                     "-DnumReruns=" + str(self.config["num_reruns"])] + extra
        try:
            proc = subprocess.run(rerun_cmd, capture_output=True, text=True,
                                  timeout=timeout, cwd=self.root)
        except subprocess.TimeoutExpired:
            self.last_verdict = "TIMEOUT"
            return ("VERDICT: TIMEOUT\nDetection did not finish within " + str(timeout) +
                    "s. The fix may hang (e.g. waits on a resource). Reconsider the fix.")
        out = proc.stdout + proc.stderr

        if proc.returncode != 0 and "Final Results" not in out:
            self.last_verdict = "DETECTION_ERROR"
            return ("VERDICT: DETECTION_ERROR\nThe detection run itself failed. Output tail:\n" +
                    truncate("\n".join(out.splitlines()[-120:])))

        if "No tests were executed" in out:
            self.last_verdict = "DETECTION_ERROR"
            return ("VERDICT: DETECTION_ERROR\nNo tests were executed - the test class may not be "
                    "compiled or the test may have been renamed. Check the test file and class name.")
        if "Possible NIO Test(s) Found" in out:
            self.last_verdict = "STILL_FLAKY"
            trace = extract_failure_trace(out)
            return ("VERDICT: STILL_FLAKY\nThe test still passes first and fails on reruns. "
                    "Failure traces:\n" + truncate(trace))
        if "Failing Test:" in out or "Non-deterministic Flaky Test(s) Found" in out:
            self.last_verdict = "BROKEN_TEST"
            trace = extract_failure_trace(out)
            return ("VERDICT: BROKEN_TEST\nThe test now fails even in the initial run (or became "
                    "non-deterministic) - the fix broke it. Failure traces:\n" + truncate(trace))
        if "No Flaky Tests Found" in out:
            self.last_verdict = "SUCCESS"
            return ("VERDICT: SUCCESS\nThe test passed the initial run and all " +
                    str(self.config["num_reruns"]) + " reruns. The NIO flakiness is fixed. "
                    "Provide your final summary now; do not call any more tools.")
        self.last_verdict = "DETECTION_ERROR"
        return ("VERDICT: DETECTION_ERROR\nCould not interpret the detection output. Tail:\n" +
                truncate("\n".join(out.splitlines()[-120:])))

    def dispatch(self, name, tool_input):
        try:
            if name == "read_file":
                return self.tool_read_file(tool_input["path"])
            if name == "write_file":
                return self.tool_write_file(tool_input["path"], tool_input["content"])
            if name == "edit_file":
                return self.tool_edit_file(tool_input["path"], tool_input["old_text"], tool_input["new_text"])
            if name == "search_code":
                return self.tool_search_code(tool_input["pattern"], tool_input.get("path"))
            if name == "list_dir":
                return self.tool_list_dir(tool_input.get("path"))
            if name == "run_detection":
                return self.tool_run_detection()
            return "Unknown tool: " + name
        except Exception as e:  # surface tool errors to the model, don't crash the loop
            return "TOOL ERROR: " + repr(e)

    # ---------- session ----------

    def initial_message(self):
        parts = ["The non-idempotent test to repair is `" + self.test_id + "`.\n"]
        test_file_path = None
        tfp_file = os.path.join(self.info_dir, "testFilePath")
        if os.path.exists(tfp_file):
            with open(tfp_file) as f:
                test_file_path = os.path.relpath(os.path.realpath(f.read().strip()), self.root)
        if test_file_path is None:
            class_simple = self.test_id.split("#")[0].split(".")[-1]
            hits = subprocess.run(
                ["find", self.root, "-name", class_simple + ".java", "-not", "-path", "*/target/*"],
                capture_output=True, text=True).stdout.strip().splitlines()
            if hits:
                test_file_path = os.path.relpath(hits[0], self.root)
        has_patch = os.path.exists(os.path.join(self.info_dir, "patch.txt"))
        if test_file_path:
            parts.append("The test lives in `" + test_file_path + "`.\n")
            abs_tf = os.path.join(self.root, test_file_path)
            # When a candidate patch exists, its (applied) content is shown below
            # instead - avoid including the large file twice.
            if os.path.exists(abs_tf) and not has_patch:
                with open(abs_tf, "r", encoding="utf-8", errors="replace") as f:
                    parts.append("Current content of the test file:\n```java\n" + f.read() + "\n```\n")

        for n in range(1, 4):
            st = os.path.join(self.info_dir, "stacktrace" + str(n))
            el = os.path.join(self.info_dir, "error_line" + str(n))
            if not os.path.exists(st):
                break
            with open(st) as f:
                parts.append("Error message in rerun #" + str(n) + ":\n```\n" + truncate(f.read(), 4000) + "\n```\n")
            if os.path.exists(el):
                with open(el) as f:
                    parts.append("The error occurs at this line:\n```\n" + f.read() + "\n```\n")

        sc = os.path.join(self.info_dir, "sourceCode")
        if os.path.exists(sc):
            with open(sc) as f:
                parts.append("Relevant main source code collected earlier (may contain cleanup helpers):\n```\n" +
                             truncate(f.read(), 12000) + "\n```\n")

        patch = os.path.join(self.info_dir, "patch.txt")
        if os.path.exists(patch) and test_file_path:
            # Pre-apply the candidate whole-file fix so the model does not have
            # to re-emit the (possibly very large) file through a tool call.
            with open(patch) as f:
                candidate = f.read()
            if not candidate.endswith("\n"):
                candidate += "\n"
            abs_tf = os.path.join(self.root, test_file_path)
            original = ""
            if os.path.exists(abs_tf):
                with open(abs_tf, "r", encoding="utf-8", errors="replace") as f:
                    original = f.read()
            self.tool_write_file(test_file_path, candidate)
            diff = "".join(difflib.unified_diff(
                original.splitlines(keepends=True), candidate.splitlines(keepends=True),
                fromfile="original/" + test_file_path, tofile="patched/" + test_file_path))
            parts.append("A candidate fix generated earlier has ALREADY been applied to `" + test_file_path +
                         "`. This unified diff shows what it changed (read the file for full context):\n```diff\n" +
                         truncate(diff, 24000) + "\n```\n")
            if len(candidate) < 30000:
                parts.append("The current (patched) content of the file is:\n```java\n" + candidate + "\n```\n")
            parts.append("First review the diff: the candidate was produced by regenerating the whole file, so it "
                         "may contain accidental changes to code unrelated to fixing `" + self.test_id + "` "
                         "(other tests, comments, formatting). Revert any such unrelated changes with edit_file. "
                         "Then call run_detection to verify the fix; if it fails, refine the fix (prefer "
                         "edit_file for targeted changes) and verify again.\n")
        else:
            parts.append("No candidate fix is available; write your own fix, then verify with run_detection.\n")
        parts.append("You have a budget of " + str(self.config["max_detection_runs"]) +
                     " run_detection calls for this test.")
        return "".join(parts)

    def restore_backups(self):
        for abs_path, original in self.backups.items():
            if original is None:
                if os.path.exists(abs_path):
                    os.remove(abs_path)
            else:
                with open(abs_path, "w", encoding="utf-8") as f:
                    f.write(original)

    def repair(self):
        messages = [{"role": "user", "content": self.initial_message()}]
        final_text = ""
        for turn in range(self.config["max_turns"]):
            with self.client.messages.stream(
                model=self.model,
                max_tokens=60000,
                system=SYSTEM_PROMPT,
                tools=TOOLS,
                messages=messages,
            ) as stream:
                response = stream.get_final_message()
            messages.append({"role": "assistant", "content": response.content})
            tool_results = []
            for block in response.content:
                if block.type == "text" and block.text.strip():
                    final_text = block.text
                    self.transcript.append({"role": "assistant", "text": block.text})
                    print("  [agent] " + block.text.splitlines()[0][:200])
                elif block.type == "tool_use":
                    over_budget = (block.name == "run_detection" and
                                   self.detection_runs >= self.config["max_detection_runs"])
                    if over_budget:
                        result = ("Budget exhausted: no run_detection calls left. "
                                  "Summarize the state and stop.")
                    else:
                        result = self.dispatch(block.name, block.input)
                    self.transcript.append({"tool": block.name,
                                            "input": {k: (v if len(str(v)) < 300 else str(v)[:300] + "...")
                                                      for k, v in block.input.items()},
                                            "result": result[:2000]})
                    print("  [tool] " + block.name +
                          (": " + result.splitlines()[0][:150] if result else ""))
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": truncate(result),
                    })
            if tool_results:
                messages.append({"role": "user", "content": tool_results})
                if self.last_verdict == "SUCCESS":
                    # Let the model produce its final summary, then stop next turn
                    continue
            else:
                # Model stopped calling tools
                break
        return self.last_verdict == "SUCCESS", final_text

    def finalize(self, success, summary):
        status = {
            "test": self.test_id,
            "success": bool(success),
            "verdict": self.last_verdict,
            "detection_runs": self.detection_runs,
            "files_touched": [os.path.relpath(p, self.root) for p in self.backups],
            "summary": summary,
        }
        with open(os.path.join(self.info_dir, "react_agent_result.json"), "w") as f:
            json.dump(status, f, indent=2)
        with open(os.path.join(self.info_dir, "react_agent_transcript.json"), "w") as f:
            json.dump(self.transcript, f, indent=2)
        if success:
            for abs_path in self.backups:
                rel = os.path.relpath(abs_path, self.root)
                dst = os.path.join(self.info_dir, "fixed_" + os.path.basename(abs_path))
                with open(abs_path, "r", encoding="utf-8", errors="replace") as src_f, \
                        open(dst, "w", encoding="utf-8") as dst_f:
                    dst_f.write(src_f.read())
                print("  kept fix in " + rel)
        else:
            self.restore_backups()
            print("  fix not confirmed - restored " + str(len(self.backups)) + " file(s)")


def extract_failure_trace(output):
    """Pull the per-rerun failure messages out of the detection console output."""
    lines = output.splitlines()
    keep = []
    capturing = False
    for line in lines:
        if "Failing Test:" in line or "Failure message:" in line:
            capturing = True
        elif capturing and (line.startswith("[INFO]") or "===" in line):
            capturing = False
        if capturing or "Possible NIO Test(s) Found" in line or "No Flaky Tests Found" in line \
                or "Final Results" in line:
            keep.append(line)
    return "\n".join(keep) if keep else "\n".join(lines[-80:])


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 react_agent.py <model> [-test=pkg.Class#method] [-timestamp=...] "
              "[-max_detection_runs=N] [-max_turns=N] [-numReruns=N] [-mvn_timeout=S] [-plugin=G:A:V]")
        sys.exit(1)

    model = MODEL_ALIASES.get(sys.argv[1], sys.argv[1])
    root = os.getcwd()
    config = {
        "max_detection_runs": 4,
        "max_turns": 40,
        "num_reruns": 3,
        "mvn_timeout": 1800,
        "plugin": DEFAULT_PLUGIN,
    }
    only_test = None
    timestamp = None
    for arg in sys.argv[2:]:
        if arg.startswith("-test="):
            only_test = arg.split("=", 1)[1]
        elif arg.startswith("-timestamp="):
            timestamp = arg.split("=", 1)[1]
        elif arg.startswith("-max_detection_runs="):
            config["max_detection_runs"] = int(arg.split("=", 1)[1])
        elif arg.startswith("-max_turns="):
            config["max_turns"] = int(arg.split("=", 1)[1])
        elif arg.startswith("-numReruns="):
            config["num_reruns"] = int(arg.split("=", 1)[1])
        elif arg.startswith("-mvn_timeout="):
            config["mvn_timeout"] = int(arg.split("=", 1)[1])
        elif arg.startswith("-plugin="):
            config["plugin"] = arg.split("=", 1)[1]
        elif not arg.startswith("-"):
            os.environ["AWS_BEARER_TOKEN_BEDROCK"] = arg

    if timestamp is None:
        timestamp = find_most_recent_run_timestamp(root)
    if timestamp is None:
        print("Error: no NIOInspector rerun logs available.")
        sys.exit(1)
    run_dir = os.path.join(root, ".NIOInspector", timestamp)
    nio_list = os.path.join(run_dir, "possible-NIO-list.txt")
    if not os.path.exists(nio_list):
        print("Error: possible-NIO-list.txt not found; run collectTestInfo first.")
        sys.exit(1)
    with open(nio_list) as f:
        tests = [line.strip() for line in f if line.strip()]
    if only_test:
        tests = [t for t in tests if t == only_test] or [only_test]

    client = make_client()
    results = {}
    for test_id in tests:
        info_dir = os.path.join(run_dir, test_id.replace("#", "."))
        if not os.path.isdir(info_dir):
            print("Skipping " + test_id + ": no collected info at " + info_dir)
            continue
        print("=== Repairing " + test_id + " ===")
        session = RepairSession(client, model, root, test_id, info_dir, config)
        try:
            success, summary = session.repair()
        except Exception as e:
            print("  session error: " + repr(e))
            success, summary = False, "session error: " + repr(e)
            session.last_verdict = session.last_verdict or "AGENT_ERROR"
        session.finalize(success, summary)
        results[test_id] = session.last_verdict
        print("=== " + test_id + " -> " + str(session.last_verdict) + " ===")

    print("\nSummary:")
    for test_id, verdict in results.items():
        print("  " + ("FIXED " if verdict == "SUCCESS" else "UNFIXED ") + test_id + " (" + str(verdict) + ")")
    sys.exit(0 if all(v == "SUCCESS" for v in results.values()) and results else 2)


if __name__ == "__main__":
    main()
