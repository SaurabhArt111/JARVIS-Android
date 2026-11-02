#!/bin/bash
# Runs the Kotlin 2.0.21 compiler that ships inside a local Gradle installation (default /opt/gradle-8.14.3; override with GRADLE_LIB=<gradle>/lib).
# Compiles are serialized with flock so parallel callers do not exhaust RAM. This exists only because the sandbox could not download the Kotlin Gradle plugin.
export JAVA_TOOL_OPTIONS=
GL="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
KCP=$GL/kotlin-compiler-embeddable-2.0.21.jar:$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlin-script-runtime-2.0.21.jar:$GL/kotlin-reflect-2.0.21.jar:$GL/kotlin-daemon-embeddable-2.0.21.jar:$GL/trove4j-1.0.20200330.jar:$GL/annotations-24.0.1.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar
exec flock "${TMPDIR:-/tmp}/kc.lock" java -Xmx2500m -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -Xjvm-default=all "$@"
