#!/bin/sh
# Сборка APK и юнит-тестов на main.worktime.kz в одноразовом контейнере (правило: сборки не на ноутбуке).
# Запуск с ПК:  sh tools/build-on-main.sh
# Результат: dist/WorkDeskAIPilot-debug.apk; на сервере после сборки ничего не остаётся.
set -eu
HOST=${HOST:?укажите HOST=пользователь@сервер}
KEY=${KEY:-$HOME/.ssh/worktime_hub}
DIR=/tmp/wdpm-build
# Keepalive: без него ssh мог висеть после окончания сборки (25.11 так и было).
SSHO="-o ServerAliveInterval=30 -o ServerAliveCountMax=6 -o ConnectTimeout=20"
cd "$(dirname "$0")/.."
tar -czf /tmp/wdpm-src.tgz -C android .
scp -q $SSHO -i "$KEY" /tmp/wdpm-src.tgz "$HOST:/tmp/wdpm-src.tgz"
rm -f /tmp/wdpm-src.tgz
ssh $SSHO -i "$KEY" "$HOST" "set -e; rm -rf $DIR; mkdir -p $DIR; tar -xzf /tmp/wdpm-src.tgz -C $DIR; rm /tmp/wdpm-src.tgz;
  timeout 40m docker run --rm --cgroup-parent=gh-builds.slice --memory=3g --cpus=2 \
    -v $DIR:/src -v /opt/android-sdk:/sdk:ro -e ANDROID_HOME=/sdk -e GRADLE_USER_HOME=/src/.gradle-home \
    -w /src gradle:8.10.2-jdk17 \
    sh -c '([ -f gradlew ] || gradle --no-daemon -q wrapper --gradle-version 8.10.2) && chmod +x gradlew && ./gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug lintDebug 2>&1 | tail -60; ls -la app/build/outputs/apk/debug/'"
mkdir -p dist
scp -q $SSHO -i "$KEY" "$HOST:$DIR/app/build/outputs/apk/debug/app-debug.apk" dist/WorkDeskAIPilot-debug.apk
# Обёртка Gradle (gradlew + gradle/wrapper), чтобы следующие сборки и CI не зависели от образа.
[ -f android/gradlew ] || ssh $SSHO -i "$KEY" "$HOST" "cd $DIR && tar -cz gradlew gradlew.bat gradle/wrapper" | tar -xz -C android
ssh $SSHO -i "$KEY" "$HOST" "rm -rf $DIR; [ -n \"${KEEP_IMAGE:-}\" ] || docker rmi -f gradle:8.10.2-jdk17 >/dev/null 2>&1 || true"
echo "готово: dist/WorkDeskAIPilot-debug.apk"
