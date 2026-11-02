#!/bin/bash
# Compile :core (main), compile its tests, run all JUnit test classes. Offline stand-in for `./gradlew :core:test` (see README: Gradle itself was never run here).
# Needs: java, a Gradle install whose lib/ has the Kotlin 2.0.21 compiler + junit-4.13.2 + hamcrest-core (GRADLE_LIB, default /opt/gradle-8.14.3/lib). Prints the JUnit summary.
# NOTE: if the test sources fail to compile, the compiler errors are printed first and JUnit then reports "OK (0 tests)": read the errors above it.
export JAVA_TOOL_OPTIONS=
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
R="${JARVIS_ROOT:-$(cd "$HERE/../.." && pwd)}"
GL="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
O="${OUT:-/tmp/jarvis-verify}/core-out"
KC="${KC:-$HERE/kc.sh}"
LIBS=$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar
JU=$GL/junit-4.13.2.jar:$GL/hamcrest-core-1.3.jar
rm -rf "$O/main" "$O/test"; mkdir -p "$O/main" "$O/test"
echo "== compiling core main"
"$KC" -d "$O/main" -cp $LIBS -jvm-target 17 $(find "$R/core/src/main" -name '*.kt') 2>&1 | grep -v "^Picked up" || true
echo "== compiling core tests"
"$KC" -d "$O/test" -cp $LIBS:"$O/main":$JU -jvm-target 17 $(find "$R/core/src/test" -name '*.kt') 2>&1 | grep -v "^Picked up" || true
CLASSES=$(cd "$O/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | tr '\n' ' ')
echo "== running $(echo $CLASSES | wc -w) test classes"
java -cp "$O/test":"$O/main":"$R/core/src/test/resources":$LIBS:$JU org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v "^Picked up" | tail -${TAIL:-40}
