#!/bin/bash
# Runs the full NIODebugger pipeline (detection + LLM fixing) on one project.
# Usage: ./run_plugin_on_project.sh <project_dir> [model] [bedrock_api_key]
#   model defaults to Sonnet4.5 (Claude Sonnet 4.5 on AWS Bedrock).
#   bedrock_api_key is optional; without it the standard AWS credential chain
#   (or the profile in $NIO_AWS_PROFILE) is used.
DIR="${PWD}"
MODEL="${2:-Sonnet4.5}"
API_KEY="$3"
PYTHON="${PYTHON:-python3}"

runPluginOnProject () {
    start_time=$(date +%s)
    cd $1
    echo "========= try to build the project $1"
    mvn install -DskipTests -Dspotbugs.skip=true | tee build.log
    sha=$(git rev-parse HEAD)
    mvn -Dexec.executable='echo' -Dexec.args='${project.artifactId}' exec:exec -q -fn | tee modnames
    if grep -q "[ERROR]" modnames; then
        echo "========= ERROR IN PROJECT $1"
	    printf '%b\n' "$1,F,,,,,,,$(( ($(date +%s)-${start_time})/60 ))" >> ${DIR}/result.csv
        exit 1
    fi
    mkdir .runNIOInspector
    mkdir ./.runNIOInspector/logs
    input="modnames"
    while IFS= read -u3 -r line; do
        echo "========= run NIOInspector in the project $1:$line"
        mvn edu.illinois:NIOInspector:rerun -pl :$line -Drat.skip=true -Dlicense.skip=true | tee ./.runNIOInspector/logs/$line.log
        log_file=./.runNIOInspector/logs/$line.log
        last_successful_line=$(grep '\[ *[0-9]* tests successful *\]' "$log_file" | tail -n 1)
        successful_tests=$(echo "$last_successful_line" | awk '{print $2}')
        last_failed_line=$(grep '\[ *[0-9]* tests failed *\]' "$log_file" | tail -n 1)
        failed_tests=$(echo "$last_failed_line" | awk '{print $2}')
        last_skipped_line=$(grep '\[ *[0-9]* tests aborted *\]' "$log_file" | tail -n 1)
        skipped_tests=$(echo "$last_skipped_line" | awk '{print $2}')
        test_count=$((successful_tests + failed_tests + skipped_tests))
        if grep -q 'Possible NIO Test(s) Found:' "$log_file"; then
            NIO_count_string=$(grep 'Possible NIO Test(s) Found' "$log_file" | tail -n 1 | awk -F ': ' '{print $2}')
            NIO_count=$((NIO_count_string))
        else
            NIO_count=0
        fi
        if [ "$NIO_count" -gt 0 ]; then
            # Steps 1-5: collect info, decide + collect relevant source code,
            # and generate whole-file candidate fixes with the LLM.
            module_dir=$(mvn help:evaluate -Dexpression=project.basedir -pl :$line -q -DforceStdout 2>/dev/null)
            module_dir="${module_dir:-$(pwd)}"
            pushd "$module_dir" > /dev/null
            mvn edu.illinois:NIOInspector:downloadFixer
            mvn edu.illinois:NIOInspector:collectTestInfo
            $PYTHON .NIOInspector/fixer.py "$MODEL" decide_relevant_source_code $API_KEY
            mvn edu.illinois:NIOInspector:collectRelevantSourceCode
            $PYTHON .NIOInspector/fixer.py "$MODEL" fix $API_KEY
            # Step 6: the ReACT agent applies each candidate fix, re-runs the
            # detection phase, and iterates on the traces until success (the
            # reflection loop that previously required rerunning this script).
            $PYTHON .NIOInspector/react_agent.py "$MODEL" $API_KEY
            popd > /dev/null

            NIO_tests=$(grep -A "$NIO_count" 'Possible NIO Test(s) Found' "$log_file" | tail -n +2 | rev | cut -d'(' -f2 | rev | awk '{print $NF}')
            while IFS= read -r NIO_test; do
                echo "https://$1,${sha},${line},${NIO_test}" >> ${DIR}/NIO_flaky_tests.csv
            done <<< "$NIO_tests"
        fi
        printf '%b\n' "$1:$line,${sha},T,${NIO_count},${test_count},${successful_tests},${failed_tests},${skipped_tests},$(( ($(date +%s)-${start_time})/60 ))" >> ${DIR}/result.csv
    done 3<"$input"
}

runPluginOnProject $1 $MODEL $API_KEY
