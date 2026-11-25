#!/bin/bash
# ─────────────────────────────────────────────────────────────
# B站切片 APK 构建：aapt2 → javac → d8 → 打入 dex → zipalign → apksigner
# 不依赖 Gradle / AndroidX / ffmpeg，产物只有几百 KB
# ─────────────────────────────────────────────────────────────
set -e
cd "$(dirname "$0")"

# Android SDK：优先环境变量，其次常见安装位置
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/AndroidSdk}}
BT=${BUILD_TOOLS:-$SDK/build-tools/34.0.0}
AJAR=$SDK/platforms/android-34/android.jar
PYTHON=${PYTHON:-python3}

# Windows 上的可执行文件带 .exe/.bat 后缀，Linux/macOS 没有，这里统一处理
pick() {
  for c in "$1.exe" "$1.bat" "$1"; do
    # 用 -e 而不是 -x：MSYS 下 .bat 常常没有可执行位
    if [ -e "$c" ]; then echo "$c"; return; fi
  done
  echo "$1"
}

# JDK：优先用环境里的 JAVA_HOME，没有就找 SDK 自带的
# 环境里的 JAVA_HOME 可能只是个空目录，得确认真的有 javac
if [ -z "$JAVA_HOME" ] || ! ls "$JAVA_HOME"/bin/javac* >/dev/null 2>&1; then
  JAVA_HOME=$(ls -d "$SDK"/jdk/* 2>/dev/null | head -1)
fi
export JAVA_HOME
JAVAC=$(pick "$JAVA_HOME/bin/javac")
KEYTOOL=$(pick "$JAVA_HOME/bin/keytool")

AAPT2=$(pick "$BT/aapt2")
D8=$(pick "$BT/d8")
ZIPALIGN=$(pick "$BT/zipalign")
APKSIGNER=$(pick "$BT/apksigner")

OUT=build
APK=clipsmith.apk
MIN_SDK=29

echo "==> 清理"
rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/flat $OUT/dex

echo "==> 1/6 编译资源"
"$AAPT2" compile --dir res -o $OUT/flat/res.zip

echo "==> 2/6 链接资源 + 生成 R.java"
"$AAPT2" link \
  -o $OUT/unaligned.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  -R $OUT/flat/res.zip \
  --java $OUT/gen \
  --auto-add-overlay \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version 34

echo "==> 3/6 编译 Java"
find src $OUT/gen -name "*.java" > $OUT/sources.txt
if ! "$JAVAC" -encoding UTF-8 -source 11 -target 11 \
     -classpath "$AJAR" -d $OUT/classes @$OUT/sources.txt > $OUT/javac.log 2>&1; then
  echo "!! 编译出错："
  "$PYTHON" -c "d=open('$OUT/javac.log','rb').read();print(d.decode('utf-8','replace'))"
  exit 1
fi

echo "==> 4/6 dex"
find $OUT/classes -name "*.class" > $OUT/classes.txt
"$D8" --lib "$AJAR" --min-api $MIN_SDK --release \
  --output $OUT/dex @$OUT/classes.txt 2>&1 | tail -3

echo "==> 5/6 打包 + 对齐"
"$PYTHON" adddex.py "$OUT/unaligned.apk" "$OUT/dex/classes.dex"
"$ZIPALIGN" -f -p 4 $OUT/unaligned.apk $OUT/aligned.apk

echo "==> 6/6 签名"
if [ ! -f debug.jks ]; then
  "$KEYTOOL" -genkeypair -keystore debug.jks \
    -storepass android -keypass android -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=CN" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks debug.jks --ks-pass pass:android \
  --key-pass pass:android --out $APK $OUT/aligned.apk

"$APKSIGNER" verify --print-certs $APK 2>/dev/null | head -2
ls -lh $APK
echo "✓ 构建完成: $(pwd)/$APK"
