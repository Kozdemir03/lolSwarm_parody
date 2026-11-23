package swarm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bir oyuncu: konum, istatistikler, girdi (klavye veya ağ), yükseltmeler, silah zamanlayıcıları. */
final class Player {
    int id, champIndex;
    boolean local;
    String label = "P1";
    Champion champ;

    float x, z, face, faceLock;
    boolean moving, downed;
    float reviveT;
    float hp = 100, maxHp = 100, regen = 0.3f, speed = 6.5f;
    float dmgMult = 1, asMult = 1, cdMult = 1, area = 1, pickup = 3.2f, armor = 0, crit = 0.05f;
    int projBonus = 0;
    float shield, shieldT, dr, drT, buffSpeed, buffAs, hurtFlash, lastHit = -99;

    // girdi: yerel oyuncuda klavye/fare, uzak oyuncuda ağdan gelir
    float inMx, inMz, mouseX, mouseZ;
    boolean lmb;
    final boolean[] castReq = new boolean[4];
    final ArrayDeque<float[]> moveQueue = new ArrayDeque<>(); // uzak oyuncu hareketleri {mx, mz, dt}

    // yetenek yardımcıları
    float aimX, aimZ, tx, tz;

    // yükseltmeler ve genel silahlar
    final LinkedHashMap<String, Upgrade> upIndex = new LinkedHashMap<>();
    final LinkedHashMap<String, Integer> ups = new LinkedHashMap<>();
    float bladeAngle, chainTimer = 1, auraTimer;

    // seviye atlama seçimi
    List<Upgrade> choices = new ArrayList<>();
    boolean picked = true;

    // istemci tarafı ağ hedefi (yumuşatma)
    float netX, netZ;

    float moveSpeed() { return speed * (1 + buffSpeed); }

    void heal(float a) { hp = Math.min(maxHp, hp + a); }

    int lvl(String id) { return ups.getOrDefault(id, 0); }

    String displayName() { return label + " " + (champ != null ? champ.name : ""); }
}
