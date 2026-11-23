package swarm;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.List;

/** Oynanabilir şampiyon: temel saldırı (otomatik) + Q E R F yetenekleri. */
abstract class Champion {
    static final float HALF_PI = (float) (Math.PI / 2);
    static final int VIS_BYTES = 8;

    final String name, title, desc;
    final int color;
    final String[] abil = new String[4];
    final String[] abilDesc = new String[4];
    final float[] cd = new float[4], cdMax = new float[4];
    float baseHp = 100, baseSpeed = 6.5f;
    float atkTimer, anim;
    /** Bu şampiyonu oynayan oyuncu. */
    Player me;

    Champion(String name, String title, String desc, int color) {
        this.name = name; this.title = title; this.desc = desc; this.color = color;
    }

    Champion bind(Player p) {
        me = p;
        return this;
    }

    /** Host/tek oyuncu: tam simülasyon. */
    void update(Game g, float dt) {
        anim += dt;
        for (int i = 0; i < 4; i++) if (cd[i] > 0) cd[i] -= dt;
        tick(g, dt);
    }

    /** İstemci: sadece görsel zamanlayıcılar (animasyon). */
    void visualTick(float dt) { anim += dt; }

    void tryCast(Game g, int slot) {
        if (cd[slot] > 0) return;
        if (cast(g, slot)) cd[slot] = effCd(slot);
    }

    /** Oyuncunun bekleme süresi azaltmasıyla gerçek bekleme süresi. */
    float effCd(int slot) {
        return cdMax[slot] * (slot == 0 && cdMax[0] < 1.5f ? 1f : (me != null ? me.cdMult : 1f));
    }

    abstract void tick(Game g, float dt);

    abstract boolean cast(Game g, int slot);

    /** base: ayak hizasında, karakterin baktığı yöne döndürülmüş matris (+Z ileri). */
    abstract void render(Renderer r, Matrix4f base, float t, boolean moving);

    void onKill(Game g, Enemy e) { }

    List<Upgrade> special() { return List.of(); }

    /** HUD'da gösterilecek ek durum satırı (null = yok). */
    String status() { return null; }

    int shieldColor() { return 0xfff3c0; }

    // ---- ağ: görsel durumu sabit 8 bayt olarak gönder/al
    void writeVis(ByteBuffer b) { }

    void readVis(ByteBuffer b) { }

    final void putVis(ByteBuffer b) {
        int start = b.position();
        writeVis(b);
        while (b.position() < start + VIS_BYTES) b.put((byte) 0);
    }

    final void getVis(ByteBuffer b) {
        int start = b.position();
        readVis(b);
        b.position(start + VIS_BYTES);
    }

    static int u8(ByteBuffer b) { return b.get() & 0xff; }

    static byte q(float v, float scale) { return (byte) Math.max(0, Math.min(255, Math.round(v * scale))); }

    // Yardımcı: yön vektörünü açıyla çevir
    static float[] dir(float dx, float dz, float angleOffset) {
        float a = (float) Math.atan2(dx, dz) + angleOffset;
        return new float[]{(float) Math.sin(a), (float) Math.cos(a)};
    }

    /** Çoklu atış için açı ofsetleri: 0, -s, +s, -2s ... */
    static float spreadOffset(int i, float step) {
        if (i == 0) return 0;
        int k = (i + 1) / 2;
        return (i % 2 == 1 ? -1 : 1) * k * step;
    }
}
