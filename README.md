# NIODetector & NIOFixer

NIODetector is a specialized Maven plugin designed to identify non-idempotent-outcome (NIO) flaky tests within Java projects. An NIO flaky test, by self-polluting a shared state, consistently passes in the initial run and fails in all subsequent executions within the same environment.

Leveraging the outputs produced by NIODetector, NIOFixer employs GPT-4 to generate a credible patch.


Prerequisites:
==============
    - Java 9 ~ 21 (NIODetector).
    - Maven 3.5+ (NIODetector).
    - Python 3.0+ (NIOFixer).


Build (NIODetector):
======

    mvn clean install


Use (NIODetector - Command-line):
============

Run the following command in the root directory of the target project after installing it  (optional arguments: use ``-Dtest=${path.to.testClass#testMethod}`` to filter for individual test classes or methods, and use ``-DnumReruns`` to configure the number of reruns for each test):

    mvn edu.illinois:NIODetector:rerun


Use (NIOFixer - Command-line):
============

    mvn edu.illinois:NIODetector:collectTestInfo
    mvn edu.illinois:NIODetector:downloadFixer
    python3 GPT_NIO_fixer.py ${your api key} ${max token for generation} ${optional additional prompt}

