#!/bin/bash
# ============================================================
#  Rift Sürüsü - Mac uygulaması (.dmg) paketleyici (Mac'te çalıştır)
#  Gerekenler: JDK 17+ ; Maven (yoksa önce IntelliJ'de Maven > package)
#  Çıktı: paketle/RiftSurusu-Mac.dmg  (içinde Java gömülü RiftSurusu.app)
# ============================================================
set -e
cd "$(dirname "$0")"

if [ ! -f ../target/rift-surusu.jar ]; then
  if command -v mvn >/dev/null 2>&1; then (cd .. && mvn -q -B package); else
    echo "../target/rift-surusu.jar yok. IntelliJ'de Maven panelinden 'package' çalıştır."; exit 1; fi
fi
command -v jpackage >/dev/null 2>&1 || { echo "jpackage bulunamadı (JDK 17+ gerekli)."; exit 1; }

JV=$(jpackage --version | cut -d. -f1)
EXTRA=()
if [ "$JV" -ge 23 ]; then EXTRA=(--java-options "--sun-misc-unsafe-memory-access=allow"); fi

rm -rf build && mkdir -p build/input
cp ../target/rift-surusu.jar build/input/

echo "JDK $JV ile paketleniyor..."
jpackage --type dmg --name RiftSurusu --input build/input --main-jar rift-surusu.jar \
  --main-class swarm.Main --dest build/dist --app-version 1.0.0 --vendor "Rift Surusu" \
  --mac-package-identifier swarm.riftsurusu \
  --add-modules java.base,java.desktop,java.management,jdk.unsupported \
  --java-options "-XstartOnFirstThread" --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Djava.awt.headless=true" "${EXTRA[@]}"

cp build/dist/RiftSurusu-*.dmg RiftSurusu-Mac.dmg
echo "Hazır: $(pwd)/RiftSurusu-Mac.dmg"
