package swarm;

import java.util.HashSet;

final class Timed {
    float t;
    final Runnable action;
    boolean done;

    Timed(float t, Runnable action) { this.t = t; this.action = action; }
}
