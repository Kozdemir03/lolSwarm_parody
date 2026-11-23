package swarm;

import java.util.HashSet;

final class Zone {
    float x, z, radius, life, max, slow;
    int color;
    Runnable onEnd;
    boolean dead;
}
