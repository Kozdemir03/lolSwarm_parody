# Rift Sürüsü — LoL Swarm parodisi (Java + OpenGL, co-op)

League of Legends'ın **Swarm** modu ve Vampire Survivors tarzında, **1–4 kişilik co-op** oynanabilen
3D bir hayatta kalma oyunu. Java 17+, LWJGL 3 (OpenGL 3.3 + GLFW) ve JOML kullanır. Tüm modeller
kodla üretilir; harici asset yok. **Mac (Apple Silicon/Intel), Windows (x64/ARM) ve Linux'ta çalışır.**

## Geliştirirken çalıştırma (IntelliJ)

1. Projeyi aç → sağ altta **"Maven build script found → Load"** (yoksa `pom.xml` → sağ tık → *Add as Maven Project*).
2. `src/main/java/swarm/Main.java` → ▶ **Run**.

macOS için gereken `-XstartOnFirstThread` bayrağını `Main` kendisi ekleyip oyunu yeniden başlatır.

## EXE / Mac uygulaması yapmak (arkadaşın Java kurmadan oynasın)

`jpackage` (JDK ile gelir) oyunu **Java gömülü** bir uygulamaya çevirir: Windows'ta `RiftSurusu.exe`,
Mac'te `RiftSurusu.app` (.dmg). Not: Windows EXE'si sadece Windows üzerinde, Mac uygulaması sadece Mac'te
üretilebilir (jpackage çapraz derleme yapmaz). Üç yol var:

**A) GitHub ile otomatik (Windows bilgisayara gerek yok, önerilen)**
1. `paketle/github-paketle.yml` dosyasını `.github/workflows/paketle.yml` olarak taşı (klasörleri oluştur).
   Projeyi GitHub'a yükle (IntelliJ: *Git → GitHub → Share Project on GitHub*).
2. GitHub'da repo → **Actions** → **Paketle** → **Run workflow**.
3. ~5 dk sonra aynı sayfanın altındaki **Artifacts** kısmından indir:
   `RiftSurusu-Windows` (içinde `RiftSurusu-Windows.zip`) ve `RiftSurusu-macOS` (`RiftSurusu-Mac.dmg`).
   `v1.0` gibi bir etiket (tag) push'layınca da kendiliğinden çalışır.

**B) Arkadaşının Windows bilgisayarında**
1. Mac'te jar'ı üret: IntelliJ → sağdaki **Maven** paneli → *Lifecycle* → **package** → `target/rift-surusu.jar`.
2. Jar'ı ve `paketle/paketle-windows.bat` dosyasını Windows'ta aynı klasöre koy (orada JDK 17+ kurulu olmalı).
3. `paketle-windows.bat`'a çift tıkla → `RiftSurusu-Windows.zip`. İçindeki `RiftSurusu\RiftSurusu.exe` çalışır.
   (EXE tek başına değil, yanındaki `app` ve `runtime` klasörleriyle birlikte çalışır; zip'i bütün olarak gönder.)

**C) Mac uygulaması:** Terminalde `paketle/paketle-mac.sh` → `paketle/RiftSurusu-Mac.dmg`.

**Sadece jar (Java kurulu olanlar için):** `java -jar target/rift-surusu.jar` ya da `dagitim/` başlatıcıları.
Jar'ın içinde Mac, Windows ve Linux kütüphanelerinin hepsi var.

İmzasız uygulama uyarıları (normal, virüs değil):
- Windows: "Windows bilgisayarınızı korudu" → **Ek bilgi → Yine de çalıştır**.
- Mac: "doğrulanmamış geliştirici" → uygulamaya **sağ tık → Aç**. (Kaldırmak için ücretli Apple/Microsoft kod imzalama sertifikası gerekir.)

## Co-op nasıl oynanır (internet üzerinden, Hamachi yok)

Oyun doğrudan **UDP** ile bağlanır, Hamachi veya kendi sunucun gerekmez.

1. Herkes ana menüde şampiyonunu seçer.
2. Bir kişi **H — Oda kur** der. Oyun, modemine **UPnP** ile kendisi port açtırır ve bir **oda kodu** gösterir (ör. `7KQ2-M9XA-4T`).
3. Kodu arkadaşına gönder (*Kopyala* butonu veya Cmd/Ctrl+C). Arkadaşın **J — Odaya katıl** der, kodu yapıştırır (Cmd/Ctrl+V), ENTER'a basar.
4. Lobide herkes görününce host, kutu boşken **ENTER**'a basar ve oyun başlar.

**Lobide "port otomatik açılamadı" yazarsa** (modemde UPnP kapalıysa): arkadaşın da kendi ekranındaki kodu
sana göndersin, sen de onu kendi kutuna yapıştır. İki taraf aynı anda birbirine paket gönderince çoğu modem
bağlantıya izin verir ("UDP hole punching").

**Yine de bağlanmazsa:** Bazı operatörler (özellikle mobil hatlar, bazı fiber hatlar) **CGNAT** kullanır;
modemin kendisi de bir ağın arkasındadır, dışarıdan gelen bağlantı hiçbir yöntemle kabul edilmez. Oyun bunu
fark ederse lobide yazar. Çözüm: operatörden "statik/gerçek IP" istemek, odayı diğer kişinin açması ya da modem
ayarlarında UDP 27015'i elle yönlendirmek. Aynı evdeyseniz lobideki "Yerel ağ" adresini girin.

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

## Güvenlik

Oyunda ağ için şu önlemler var:

- **Kod çalıştırma riski yok.** Gelen paketler Java nesnesi olarak açılmaz (serialization yok); sadece sayı ve kısa metin
  okunur, her alan sınırlanır. Java bellek güvenli olduğu için C/C++ oyunlardaki "buffer overflow" türü açıklar oluşmaz.
  Bozuk paket gelirse yok sayılır. Test: oyun boyunca saniyede ~180 rastgele/bozuk paket gönderildi, oyun çökmedi.
- **Rastgele paketle oyuna karışılamaz.** Her oyuncuya bağlanırken rastgele bir oturum anahtarı verilir; anahtarı olmayan
  girdi paketi atılır. İstemci de sadece bağlandığı host'tan gelen veriyi kabul eder.
- **Hız hilesi yok.** Host hareketi kendisi hesaplar ve geçen gerçek süreden fazla hareketi reddeder.
- **Oda en fazla 4 kişi** ve sadece lobideyken katılınabilir.
- **Port açıkta kalmaz.** UPnP ile açılan port 2 saatlik kiralanır ve odadan çıkınca/oyun kapanınca silinir.
  Port sadece oyun açıkken dinlenir.

Bilmen gerekenler:

- **Oda kodu = genel IP adresin.** Kodu sadece birlikte oynayacağın kişilere gönder, herkese açık yerlerde paylaşma
  (IP adresinle yaklaşık konumun bulunabilir ve bağlantına yük bindirilebilir).
- **Host'a güvenin.** Oyunu host hesapladığı için host hile yapabilir (diğerleri yapamaz). Arkadaşlar arası oyun için sorun değil.
- **Trafik şifreli değil.** Ağı dinleyen biri oyun hareketlerini görebilir; içinde şifre veya kişisel bilgi yok.
- **UPnP modemin bir özelliği.** Oyun sadece kendi portunu açıp kapatır; ama modemde UPnP açık olması, bilgisayarındaki
  başka programların da port açabileceği anlamına gelir. İstemezsen modemde kapat; o zaman kod alışverişi yöntemi kullanılır.
- Windows güvenlik duvarı sorarsa **Özel ağlar**'a izin ver. Ev Wi-Fi'ın Windows'ta "Ortak/Genel ağ" olarak ayarlıysa onu da işaretle; kafe gibi gerçekten ortak ağlarda host olma.

## Kod yapısı

| Dosya | İçerik |
|---|---|
| `Main` | Giriş noktası; macOS/Windows için JVM'i doğru bayraklarla yeniden başlatma |
| `Game` | Oyun döngüsü, durumlar, oyuncular, spawn, çarpışma, efektler, çizim, HUD, lobi |
| `Net` | UDP soket, STUN (genel adres keşfi), oda kodu |
| `Upnp` | Modemde otomatik port açma/kapama (UPnP IGD), CGNAT tespiti |
| `NetSession` | Co-op protokolü: lobi, girdi, dünya durumu, efekt olayları, istemci tahmini |
| `Champion` + `Kivilcim`, `DemirMuhafiz`, `Lumina` | Şampiyon kitleri, 3D modeller, ağ görsel durumu |
| `Player` | Oyuncu istatistikleri, girdi, yükseltmeler |
| `Renderer`, `Shader`, `Mesh`, `MeshFactory` | Işıklandırmalı 3D çizim, prosedürel meshler |
| `UI`, `Font` | 2D arayüz, Türkçe destekli font atlası |

> Riot Games ile ilgisi olmayan, eğitim amaçlı bir hayran parodisidir; karakterler orijinal
> isimlere ve modellere sahiptir, sadece yetenek fikirleri LoL'den esinlenmiştir.
