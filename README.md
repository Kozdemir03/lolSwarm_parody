# Rift Sürüsü — LoL Swarm parodisi (Java + OpenGL, co-op)

League of Legends'ın **Swarm** modu ve Vampire Survivors tarzında, **1–4 kişilik co-op** oynanabilen
3D bir hayatta kalma oyunu. Java 17+, LWJGL 3 (OpenGL 3.3 + GLFW) ve JOML kullanır. Tüm modeller
kodla üretilir; harici asset yok. **Mac (Apple Silicon/Intel), Windows (x64/ARM) ve Linux'ta çalışır.**

## Geliştirirken çalıştırma (IntelliJ)

1. Projeyi aç → sağ altta **"Maven build script found → Load"** (yoksa `pom.xml` → sağ tık → *Add as Maven Project*).
2. `src/main/java/swarm/Main.java` → ▶ **Run**.

macOS için gereken `-XstartOnFirstThread` bayrağını `Main` kendisi ekleyip oyunu yeniden başlatır.

## Arkadaşına göndermek için tek dosya (Mac + Windows)

```
mvn package
```
(IntelliJ'de: sağdaki **Maven** paneli → *Lifecycle* → **package**.)

Çıktı: `target/rift-surusu.jar`. İçinde tüm platformların kütüphaneleri var, aynı dosya her yerde çalışır.

- Arkadaşının bilgisayarında **Java 17 veya üstü** olmalı (ücretsiz: <https://adoptium.net> → Temurin).
- Çalıştırma: `java -jar rift-surusu.jar` ya da jar'ın yanına `dagitim/` klasöründeki başlatıcıyı koyup çift tıkla:
  - Windows: `RiftSurusu.bat`
  - Mac: `RiftSurusu.command`

## Co-op nasıl oynanır (internet üzerinden, Hamachi yok)

Oyun doğrudan **UDP** ile bağlanır. Kendi sunucu gerekmez: her oyun, herkese açık bir STUN sunucusuna
sorup kendi internet adresini öğrenir ve bunu bir **oda kodu** olarak gösterir (ör. `7KQ2-M9XA-4T`).

1. Herkes ana menüde şampiyonunu seçer.
2. Bir kişi **H — Oda kur** der. Diğerleri **J — Odaya katıl** der.
3. **Kodları birbirinize gönderin** (WhatsApp, Discord...). Ekrandaki kodu *Kopyala* butonu veya Cmd/Ctrl+C ile kopyalayabilirsin.
4. Katılan kişi host'un kodunu yapıştırır (Cmd/Ctrl+V) → ENTER.
   Host da katılanın kodunu yapıştırır → ENTER. *(İki taraf da birbirine paket atınca modemler bağlantıya izin verir: "UDP hole punching".)*
5. Lobide herkes görününce host, kutu boşken **ENTER**'a basar ve oyun başlar.

**Bağlanmazsa:** Bazı modem/operatörlerde (özellikle CGNAT veya "simetrik NAT" kullananlar, bazı mobil hatlar)
delik açma çalışmaz. O zaman host, modeminde **UDP 27015** portunu kendi bilgisayarına yönlendirsin
(port forwarding). Bunu yaptıktan sonra yalnızca katılan kişinin host kodunu girmesi yeter.
Aynı evdeyseniz lobide yazan "Yerel ağ" adresini de girebilirsiniz.

İlk açılışta **Windows Güvenlik Duvarı / macOS** "Java gelen bağlantıları kabul etsin mi?" diye sorarsa **İzin ver** de.

### Co-op kuralları
- Tecrübe ve seviye takımca paylaşılır; seviye atlayınca **herkes kendi kartını seçer**, herkes seçene kadar oyun durur.
- Canı biten oyuncu **düşer**; bir takım arkadaşı yanında 3 sn durursa %40 canla kalkar. Herkes düşerse oyun biter.
- Oyuncu sayısı arttıkça düşmanlar daha sık gelir ve daha dayanıklı olur.
- Oyunu host hesaplar. Diğer oyuncular kendi hareketlerini anında görür (tahmin), kalan her şey host'tan gelir.
  Saniyede ~20 güncelleme, oyuncu başına kabaca 20–40 KB/sn.

## Kontroller

| Tuş | İşlev |
|---|---|
| W A S D / ok tuşları | Hareket |
| Temel saldırı | Otomatik (en yakın düşmana) |
| Sol tık basılı | Temel saldırıyı fareye doğrult |
| Q E R F | Yetenekler (fare yönüne/noktasına) |
| 1 / 2 / 3 veya tık | Seviye atlayınca güçlendirme seç |
| ESC | Tek oyuncuda duraklat, co-op'ta menü (oyun arkada devam eder) |

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
- Her dakika **elit** düşman (taçlı, sarı) gelir; tecrübe yağmuru, can ve mıknatıs düşürür.
- Belli aralıklarla etrafınızı saran **sürü dalgaları**.
- **8:00**'de **Void Titanı** uyanır: her oyuncuya yere çarpma, minyon çağırma + mermi halkası, hücum. Onu yenin → zafer.

## Kod yapısı

| Dosya | İçerik |
|---|---|
| `Main` | Giriş noktası; macOS/Windows için JVM'i doğru bayraklarla yeniden başlatma |
| `Game` | Oyun döngüsü, durumlar, oyuncular, spawn, çarpışma, efektler, çizim, HUD, lobi |
| `Net` | UDP soket, STUN (genel adres keşfi), oda kodu |
| `NetSession` | Co-op protokolü: lobi, girdi, dünya durumu, efekt olayları, istemci tahmini |
| `Champion` + `Kivilcim`, `DemirMuhafiz`, `Lumina` | Şampiyon kitleri, 3D modeller, ağ görsel durumu |
| `Player` | Oyuncu istatistikleri, girdi, yükseltmeler |
| `Renderer`, `Shader`, `Mesh`, `MeshFactory` | Işıklandırmalı 3D çizim, prosedürel meshler |
| `UI`, `Font` | 2D arayüz, Türkçe destekli font atlası |

> Riot Games ile ilgisi olmayan, eğitim amaçlı bir hayran parodisidir; karakterler orijinal
> isimlere ve modellere sahiptir, sadece yetenek fikirleri LoL'den esinlenmiştir.
