package swarm;

/** Host'ta oluşan görsel efekt; istemcilere gönderilip orada yeniden üretilir. */
final class FxEvent {
    static final int BURST = 0, SPARK = 1, RING = 2, TEXT = 3, BANNER = 4, SHAKE = 5;

    int type, color, n;
    float x, y, z, a, b, c;
    String s;

    FxEvent(int type) { this.type = type; }

    boolean positional() { return type != BANNER && type != SHAKE; }
}
