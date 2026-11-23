#!/bin/bash
# Rift Sürüsü - Mac başlatıcı. rift-surusu.jar bu dosyayla aynı klasörde olmalı.
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "Java bulunamadı. https://adoptium.net adresinden Java 17 veya üstünü kurun."
  read -n 1 -s -r -p "Kapatmak için bir tuşa bas..."
  exit 1
fi
java -jar rift-surusu.jar
