package swarm;

import java.util.HashSet;

final class Player {
    float x, z, face, faceLock, anim;
    boolean moving;
    float hp = 100, maxHp = 100, regen = 0.3f, speed = 6.5f;
    float dmgMult = 1, asMult = 1, cdMult = 1, area = 1, pickup = 3.2f, armor = 0, crit = 0.05f;
    int projBonus = 0;
    float shield, shieldT, dr, drT, buffSpeed, buffAs, hurtFlash, lastHit = -99;
    int level = 1;
    float xp, xpNext;

    float moveSpeed() { return speed * (1 + buffSpeed); }

    void heal(float a) { hp = Math.min(maxHp, hp + a); }
}
