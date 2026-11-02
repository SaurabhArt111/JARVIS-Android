#!/bin/bash
# Compile :core + :app Kotlin sources against the real android.jar (API 36), with a stub R/BuildConfig. No Gradle/AGP needed.
# This proves the Kotlin type-checks against the Android framework API; it does NOT replace a real Gradle build.
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${JARVIS_ROOT:-$(cd "$HERE/../.." && pwd)}"
GL="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
ANDROID_JAR="${ANDROID_JAR:-/home/claude/third_party/aosp-android-jar/android-36/android.jar}"   # AOSP-built android.jar for API 36; set ANDROID_JAR to any SDK platform android.jar
O="${OUT:-/tmp/jarvis-verify}"
KC="${KC:-$HERE/kc.sh}"
NS=com.jarvis.assistant
LIBS=$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar
mkdir -p $O && rm -rf $O/app $O/gen && mkdir -p $O/app $O/gen
python3 -I $HERE/gen_r.py $ROOT/app $O/gen $NS 0.1.0 || exit 2
echo "== compiling core + app (against $(basename $(dirname $ANDROID_JAR)))"
JAVA_SRC="$O/gen/com/jarvis/assistant"
# Hand-written stubs of the few ONNX Runtime classes used by ONE app file (the real AAR cannot be downloaded here). Never shipped.
rm -rf $O/stubs && mkdir -p $O/stubs
javac -d $O/stubs $(find $HERE/stubs -name '*.java') || { echo "STUB COMPILE FAILED"; exit 3; }
$KC -d $O/app -cp $LIBS:$ANDROID_JAR:$O/stubs -jvm-target 17 \
  $(find $ROOT/core/src/main $ROOT/app/src/main -name '*.kt') $JAVA_SRC/R.java $JAVA_SRC/BuildConfig.java 2>&1 | grep -v "^Picked up"
RC=${PIPESTATUS[0]}
[ "$RC" != "0" ] && { echo "KOTLIN COMPILE FAILED ($RC)"; exit $RC; }
echo "== resource / manifest checks"
APP_NAMESPACE=$NS python3 -I $HERE/check_res.py $ROOT/app $O/app
