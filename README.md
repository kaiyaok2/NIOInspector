# NIOInspector

NIOInspector is a specialized Maven plugin designed to identify and fix non-idempotent-outcome (NIO) flaky tests within Java projects. An NIO flaky test, by self-polluting a shared state, consistently passes in the initial run and fails in all subsequent executions within the same environment.

The plugin detects NIO tests using a custom JUnit runner that supports repeating tests in the same JVM, and then employs GPT-4 to generate a credible patch.


Prerequisites:
==============
    - Java 9 ~ 21 (Detection).
    - Maven 3.5+ (Detection).
    - Python 3.0+ (Test fixing).


Build (NIOInspector):
======

    mvn clean install


Use (NIOInspector - Command-line):
============

Run the following command in the root directory of the target project after installing it  (optional arguments: use ``-Dtest=${path.to.testClass#testMethod}`` to filter for individual test classes or methods, and use ``-DnumReruns`` to configure the number of reruns for each test):

    mvn edu.illinois:NIOInspector:rerun


Use (NIOFixer - Command-line):
============

    mvn edu.illinois:NIOInspector:collectTestInfo
    mvn edu.illinois:NIOInspector:downloadFixer
    python3 GPT_NIO_fixer.py ${your api key} ${max token for generation} ${optional additional prompt}

