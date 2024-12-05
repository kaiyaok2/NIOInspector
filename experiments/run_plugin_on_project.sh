#!/bin/bash
DIR="${PWD}"
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
        for i in {1..3}; do 
            echo "========= run NIOInspector in the project $1:$line"
            if [ "$i" -ge 2 ]; then
                mvn clean install -DskipTests -pl :$line -am -Drat.skip=true -Dlicense.skip=true
                mvn edu.illinois:NIOInspector:rerun -pl :$line -Drat.skip=true -Dlicense.skip=true
            else
                mvn edu.illinois:NIOInspector:rerun -pl :$line -Drat.skip=true -Dlicense.skip=true | tee ./.runNIOInspector/logs/$line.log
            fi
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
                mvn edu.illinois:NIOInspector:downloadFixer -pl :$line
                mvn edu.illinois:NIOInspector:collectTestInfo -pl :$line
                if [[ "$2" == GPT* ]]; then
                    python3 .NIOInspector/fixer.py $2 decide_relevant_source_code $3
                    mvn edu.illinois:NIOInspector:collectRelevantSourceCode -pl :$line
                    python3 .NIOInspector/fixer.py $2 fix $3
                elif [[ "$2" == DeepSeek* || "$2" == Qwen* ]]; then
                    python3 .NIOInspector/fixer.py $2 decide_relevant_source_code
                    mvn edu.illinois:NIOInspector:collectRelevantSourceCode -pl :$line
                    python3 .NIOInspector/fixer.py $2 fix
                else
                    echo "Unsupported model. Not generating patches."
                fi
                NIO_tests=$(grep -A "$NIO_count" 'Possible NIO Test(s) Found' "$log_file" | tail -n +2 | rev | cut -d'(' -f2 | rev | awk '{print $NF}')
                [ -f unfixed_NIO_tests.csv ] && rm unfixed_NIO_tests.csv
                touch unfixed_NIO_tests.csv
                echo "Project URL,SHA Detected,Subproject Name,Fully-Qualified Test Name (packageName.ClassName#methodName)" >> unfixed_NIO_tests.csv
                while IFS= read -r NIO_test; do
                    if [ "$i" -lt 2 ]; then
                        echo "https://$1,${sha},${line},${NIO_test}" >> ${DIR}/NIO_flaky_tests.csv
                    fi
                    echo "https://$1,${sha},${line},${NIO_test}" >> unfixed_NIO_tests.csv
                done <<< "$NIO_tests"
		        cur_dir=$(pwd)
                cd ${DIR}
                if [[ "$2" == GPT* ]]; then
                    ./apply_nios.sh ${cur_dir}/unfixed_NIO_tests.csv $2 $3
                elif [[ "$2" == DeepSeek* || "$2" == Qwen* ]]; then
                    ./apply_nios.sh ${cur_dir}/unfixed_NIO_tests.csv $2
                else
                    echo "Unsupported model. Not searching for patches to apply."
                fi
                cd "$cur_dir"
            fi
            if [ "$i" -lt 2 ]; then
                printf '%b\n' "$1:$line,${sha},T,${NIO_count},${test_count},${successful_tests},${failed_tests},${skipped_tests},$(( ($(date +%s)-${start_time})/60 ))" >> ${DIR}/result.csv
            fi
        done
    done 3<"$input"
}

runPluginOnProject $1 $2 $3
