#!/bin/bash
DIR="${PWD}"
touch result.csv
touch NIO_flaky_tests.csv
echo "project name,SHA,compile,NIO flaky tests,total tests,successful tests,failed tests,skipped tests,time (minutes)" > result.csv
echo "Project URL,SHA Detected,Subproject Name,Fully-Qualified Test Name (packageName.ClassName#methodName)" > NIO_flaky_tests.csv


for repo in $(cat $1); do
    user=$(dirname $repo)
    cur_repo=$(basename $repo)
    dir=github.com/${user}/${cur_repo}
    url=http://github.com/${user}/${cur_repo}.git
    git clone $url ${dir}
    echo $repo
    ./run_plugin_on_project.sh ${dir} $2 $3
done
