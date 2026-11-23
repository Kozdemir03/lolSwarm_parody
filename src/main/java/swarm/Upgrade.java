package swarm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class Upgrade {
    final String id, name, desc;
    final int max, color;
    final Consumer<Game> apply;

    Upgrade(String id, String name, String desc, int max, int color, Consumer<Game> apply) {
        this.id = id; this.name = name; this.desc = desc; this.max = max; this.color = color; this.apply = apply;
    }

    static List<Upgrade> generic() {
        List<Upgrade> l = new ArrayList<>();
        l.add(new Upgrade("guc", "Güç", "Tüm hasarın +%15 artar.", 8, 0xffe05a5a, g -> g.player.dmgMult += 0.15f));
        l.add(new Upgrade("hiz", "Saldırı Hızı", "Temel saldırı hızı +%15.", 8, 0xffffb347, g -> g.player.asMult += 0.15f));
        l.add(new Upgrade("kosu", "Çeviklik", "Hareket hızı +%8.", 5, 0xff7fe0ff, g -> g.player.speed *= 1.08f));
        l.add(new Upgrade("can", "Dayanıklılık", "Maks. can +25 ve anında 25 can.", 8, 0xff5ad17a, g -> {
            g.player.maxHp += 25;
            g.player.heal(25);
        }));
        l.add(new Upgrade("yenile", "Yenilenme", "Saniyede +0.8 can yenilenir.", 5, 0xff3fbf6a, g -> g.player.regen += 0.8f));
        l.add(new Upgrade("miknatis", "Mıknatıs", "Tecrübe toplama alanı +%35.", 4, 0xff6aa8ff, g -> g.player.pickup *= 1.35f));
        l.add(new Upgrade("odak", "Odak", "Tüm yetenek bekleme süreleri -%8.", 5, 0xffb07aff, g -> g.player.cdMult *= 0.92f));
        l.add(new Upgrade("alan", "Etki Alanı", "Patlamalar ve alan etkileri +%12 büyür.", 5, 0xffff8ad8, g -> g.player.area += 0.12f));
        l.add(new Upgrade("zirh", "Zırh", "Alınan hasar -%6.", 5, 0xffa0a8b8, g -> g.player.armor += 0.06f));
        l.add(new Upgrade("krit", "Kritik Vuruş", "Kritik şansı +%8 (kritik = 2x hasar).", 5, 0xffffd84a, g -> g.player.crit += 0.08f));
        l.add(new Upgrade("coklu", "Çoklu Atış", "Temel saldırılar +1 mermi daha atar.", 3, 0xffff6fb0, g -> g.player.projBonus++));
        l.add(new Upgrade("blades", "Dönen Kılıçlar", "Etrafında dönen kılıçlar. Her seviye +1 kılıç ve daha çok hasar.", 5, 0xffd0d8e8, g -> { }));
        l.add(new Upgrade("chain", "Şimşek Zinciri", "Düzenli olarak düşmandan düşmana sıçrayan şimşek.", 5, 0xff9fdcff, g -> { }));
        l.add(new Upgrade("aura", "Buz Halkası", "Yakındaki düşmanlara sürekli hasar ve %30 yavaşlatma.", 5, 0xff7fd8ff, g -> { }));
        return l;
    }

    static Upgrade fallback() {
        return new Upgrade("iksir", "Can İksiri", "Canının %40'ını yenile.", 9999, 0xffff5a7a, g -> g.player.heal(g.player.maxHp * 0.4f));
    }
}
