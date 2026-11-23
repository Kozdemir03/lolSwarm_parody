package swarm;

import java.util.HashSet;

final class Enemy {
    int id;
    float nx, nz; // istemci: ağdan gelen hedef konum
    EnemyKind kind;
    float x, z, hp, maxHp, speed, dmg, radius, face;
    int xp;
    boolean elite, dead;
    float flash, slowT, slow, rootT, markT, atkCd, anim, bladeCd, kx, kz, spawnT;
    // boss
    float bossT = 3f, chargeT, cdx, cdz, windT;
    int bossPhase;
}
