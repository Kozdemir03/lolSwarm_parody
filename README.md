# Rift Sürüsü — LoL Swarm parodisi (Java + OpenGL)

League of Legends'ın **Swarm** modu ve Vampire Survivors tarzında bir 3D hayatta kalma oyunu.
Java 17+, LWJGL 3 (OpenGL 3.3 core + GLFW) ve JOML kullanır. Tüm modeller kodla, ilkel
şekillerden (küp, küre, silindir, koni) üretilir; harici asset yok.

## Çalıştırma (IntelliJ IDEA)

1. Projeyi IntelliJ'de aç. Sağ altta **"Maven build script found → Load"** çıkarsa tıkla.
   Çıkmazsa `pom.xml` dosyasına sağ tıkla → **Add as Maven Project**.
2. Maven bağımlılıkları indirdikten sonra `src/main/java/swarm/Main.java` dosyasını aç ve ▶ **Run**.

macOS'ta GLFW için gereken `-XstartOnFirstThread` bayrağını `Main` kendisi ekleyip oyunu yeniden
başlatır, ekstra ayar gerekmez. (Debugger ile çalıştırmak istersen Run Configuration → VM options:
`-XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow`)

Terminalden: `mvn compile exec:exec`

## Kontroller

| Tuş | İşlev |
|---|---|
| W A S D / ok tuşları | Hareket |
| Temel saldırı | Otomatik (en yakın düşmana) |
| Sol tık basılı | Temel saldırıyı fareye doğrult |
| Q E R F | Yetenekler (fare yönüne/noktasına) — W hareket olduğu için 2. yetenek E'de, ultiler F'de |
| 1 / 2 / 3 veya tık | Seviye atlayınca güçlendirme seç |
| ESC / P | Duraklat |

## Şampiyonlar

**Kıvılcım** (Jinx benzeri) — mavi örgülü, kaotik nişancı
- **Q Silah Değiştir:** Pow-Pow minigun (ateş ettikçe hızlanır) ↔ Balıksırtı köpekbalığı roketatar (alan hasarı)
- **E Zap!:** Her şeyi delip geçen, yavaşlatan elektrik ışını
- **R Alev Çiğneyiciler:** Basınca patlayıp kökleyen tuzaklar
- **F Süper Mega Ölüm Roketi:** Uzağa gittikçe güçlenen dev roket
- **Pasif Coşku!:** Belli sayıda öldürmede / elit öldürünce büyük hız patlaması

**Demir Muhafız** (Garen benzeri) — dev kılıçlı tank: güçlendirilmiş darbe, kalkan, dönen Yargı, gökten düşen infaz kılıcı.

**Lumina** (Lux benzeri) — ışık büyücüsü: bağlayıcı küre, kalkan, yavaşlatan/patlayan alan, devasa lazer. Yetenekler düşmanı işaretler, işaretliye temel saldırı ekstra patlar.

## Oyun akışı

- Düşmanlar zamanla çoğalır ve güçlenir: minyon, koşucu, iri dev, tükürücü.
- Her dakika **elit** düşman (taçlı, sarı) gelir; öldürünce tecrübe yağmuru, can ve mıknatıs düşürür.
- Belli aralıklarla etrafını saran **sürü dalgaları**.
- **8:00**'de **Void Titanı** uyanır: yere çarpma, minyon çağırma + mermi halkası, hücum. Onu yen → zafer.
- Seviye atladıkça 3 güçlendirmeden birini seçersin: genel istatistikler, ortak silahlar
  (Dönen Kılıçlar, Şimşek Zinciri, Buz Halkası) ve şampiyona özel geliştirmeler.

## Kod yapısı

| Dosya | İçerik |
|---|---|
| `Main` | Giriş noktası, macOS için JVM'i doğru bayraklarla yeniden başlatma |
| `Game` | Oyun döngüsü, durumlar, spawn, çarpışma (uzaysal ızgara), efektler, çizim, HUD |
| `Champion` + `Kivilcim`, `DemirMuhafiz`, `Lumina` | Şampiyon kitleri ve 3D modelleri |
| `Renderer`, `Shader`, `Mesh`, `MeshFactory` | Işıklandırmalı 3D çizim, prosedürel meshler |
| `UI`, `Font` | 2D arayüz, AWT ile üretilen Türkçe destekli font atlası |
| `Upgrade` | Güçlendirme havuzu |
| `Enemy`, `Projectile`, `Orb`, … | Basit veri sınıfları |

Yeni şampiyon eklemek için `Champion`'dan türet, `Game.create()` içine ekle ve menüdeki
`previews` dizisini büyüt.

> Riot Games ile ilgisi olmayan, eğitim amaçlı bir hayran parodisidir; karakterler orijinal
> isimlere ve modellere sahiptir, sadece yetenek fikirleri LoL'den esinlenmiştir.
