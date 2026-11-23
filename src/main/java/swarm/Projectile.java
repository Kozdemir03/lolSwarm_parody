package swarm;

import java.util.HashSet;

final class Projectile {
    int id;
    Player owner;
    ProjKind kind;
    float x, y = 1.0f, z, vx, vz, dmg, radius, range, traveled, splash, slow, slowT, root, markBonus, knock, age;
    int pierce, color;
    boolean enemy, dead, mark;
    HashSet<Enemy> hit;
}
