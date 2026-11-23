package swarm;

import java.util.HashSet;

enum EnemyKind {
    //        can   hız   hasar yarıçap xp  renk
    MINION(20, 3.2f, 8, 0.55f, 1, 0x8a4dd6),
    RUNNER(14, 5.4f, 6, 0.45f, 1, 0xe8613a),
    BRUTE(95, 2.3f, 16, 1.0f, 4, 0x3a4f9e),
    SPITTER(32, 3.0f, 10, 0.6f, 2, 0x5cc44a),
    BOSS(7000, 2.9f, 28, 2.6f, 0, 0x5a1a7a);

    final float hp, speed, dmg, radius;
    final int xp, color;

    EnemyKind(float hp, float speed, float dmg, float radius, int xp, int color) {
        this.hp = hp; this.speed = speed; this.dmg = dmg; this.radius = radius; this.xp = xp; this.color = color;
    }
}
