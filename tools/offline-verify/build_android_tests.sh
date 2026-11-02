#!/bin/bash
# Type-checks app/src/androidTest against the real android.jar + JUnit, using a tiny stub of androidx.test's InstrumentationRegistry (the real library is not
# available offline). This proves the instrumented tests COMPILE against the Android API and the app code; it does NOT run them (that needs a device/emulator).
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${JARVIS_ROOT:-$(cd "$HERE/../.." && pwd)}"
GL="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
ANDROID_JAR="${ANDROID_JAR:-/home/claude/third_party/aosp-android-jar/android-36/android.jar}"
O="${OUT:-/tmp/jarvis-verify}"
KC="${KC:-$HERE/kc.sh}"
LIBS=$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar
JUNIT=$(ls $GL/junit-4*.jar 2>/dev/null | head -1)
[ -z "$JUNIT" ] && JUNIT=$(find / -name 'junit-4*.jar' 2>/dev/null | head -1)
[ -d $O/app ] || { echo "run build_app.sh first"; exit 2; }
STUB=$O/stub-androidx; rm -rf $STUB $O/androidtest; mkdir -p $STUB $O/androidtest
cat > $STUB/Stub.kt <<'KT'
package androidx.test.platform.app
object InstrumentationRegistry { fun getInstrumentation(): android.app.Instrumentation = throw UnsupportedOperationException("stub") }
KT
echo "== compiling androidTest (stubbed androidx.test)"
$KC -d $O/androidtest -cp $LIBS:$ANDROID_JAR:$JUNIT:$O/app -jvm-target 17 $STUB/Stub.kt $(find $ROOT/app/src/androidTest -name '*.kt') 2>&1 | grep -v "^Picked up"
exit ${PIPESTATUS[0]}
