package swarm;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

import java.util.List;

/**
 * Jinx benzeri: Minigun / roketatar arasında geçiş, Zap!, alev çiğneyici tuzaklar,
 * haritanın öbür ucuna giden süper mega ölüm roketi ve öldürdükçe gelen "Coşku!" pasifi.
 */
final class Kivilcim extends Champion {
    static final int BLUE = 0x2f6fe0, BLUE2 = 0x2457c2, PINK = 0xff4fa3, SKIN = 0xf4d6c6, DARK = 0x1d1b2e;
    static final int TEAL = 0x2fb39a, TEAL2 = 0x1f8c78, GRAY = 0x9aa3ad, GUN = 0x4b5160;

    boolean rocketMode;
    int stacks, shotCount, killCounter;
    float lastShot = 99, spin, excitedT, swapAnim;
    // yükseltilebilir değerler
    int maxStacks = 3, chompers = 3, zapExtra = 0, exciteKills = 25;
    float rocketSplash = 2.4f, rocketDmg = 24, zapDmg = 65, megaDmg = 180, exciteDur = 4f, minigunDmg = 9;

    Kivilcim() {
        super("Kıvılcım", "Kaotik Silah Manyağı",
                "Her şeyi havaya uçurmayı seven, mavi örgülü çılgın nişancı. Minigun ile hızlan, roketle kalabalığı dağıt!",
                PINK);
        abil[0] = "Silah Değiştir";
        abilDesc[0] = "Pow-Pow minigun (ateş ettikçe hızlanır) ile Balıksırtı roketatar (alan hasarı, uzun menzil) arasında geçiş.";
        abil[1] = "Zap!";
        abilDesc[1] = "Kısa hazırlıktan sonra fareye doğru her şeyi delen elektrik ışını; vurduklarını %60 yavaşlatır.";
        abil[2] = "Alev Çiğneyiciler";
        abilDesc[2] = "Fare noktasına 3 tuzak atar. Düşman basınca patlar, hasar verir ve kökler.";
        abil[3] = "Süper Mega Ölüm Roketi";
        abilDesc[3] = "Fare yönüne dev roket. İlk çarptığında patlar; ne kadar uzağa giderse o kadar çok hasar.";
        cdMax[0] = 0.8f; cdMax[1] = 6f; cdMax[2] = 11f; cdMax[3] = 40f;
        baseHp = 100;
        baseSpeed = 6.8f;
    }

    @Override
    void tick(Game g, float dt) {
        Player p = me;
        lastShot += dt;
        if (lastShot > 2f) { stacks = 0; shotCount = 0; }
        if (swapAnim > 0) swapAnim -= dt;

        // Pasif: Coşku!
        if (excitedT > 0) {
            excitedT -= dt;
            p.buffSpeed = 0.2f + 0.5f * Math.max(0, excitedT / exciteDur);
            p.buffAs = 0.8f;
            if (g.rng.nextFloat() < dt * 25)
                g.spark(p.x + g.rng.nextFloat() - .5f, 0.3f + g.rng.nextFloat() * 1.6f, p.z + g.rng.nextFloat() - .5f, g.rng.nextBoolean() ? PINK : 0x7ff9ff);
        } else {
            p.buffSpeed = 0;
            p.buffAs = 0;
        }

        if (!rocketMode && lastShot < 0.35f) spin += dt * (14 + stacks * 8);

        float as = p.asMult * (1 + p.buffAs) * (rocketMode ? 1f : 1f + 0.35f * stacks);
        atkTimer -= dt * as;
        if (atkTimer <= 0) {
            float range = rocketMode ? 17 : 13;
            if (g.aim(me, range)) {
                fire(g);
                atkTimer = rocketMode ? 1f / 0.95f : 1f / 2.6f;
            } else atkTimer = 0;
        }
    }

    private void fire(Game g) {
        Player p = me;
        float dx = p.aimX - p.x, dz = p.aimZ - p.z;
        int n = 1 + p.projBonus;
        lastShot = 0;
        for (int i = 0; i < n; i++) {
            float[] d = dir(dx, dz, spreadOffset(i, 0.14f));
            if (rocketMode) {
                Projectile pr = g.shoot(me, ProjKind.ROCKET, p.x, p.z, d[0], d[1], 21, rocketDmg, 18, 0.35f, TEAL);
                pr.splash = rocketSplash * p.area;
                pr.y = 1.4f;
            } else {
                Projectile pr = g.shoot(me, ProjKind.BULLET, p.x, p.z, d[0], d[1], 30, minigunDmg, 14, 0.25f, PINK);
                pr.knock = 1.5f;
            }
        }
        if (!rocketMode) {
            shotCount++;
            if (shotCount % 3 == 0 && stacks < maxStacks) stacks++;
        }
    }

    @Override
    boolean cast(Game g, int slot) {
        Player p = me;
        switch (slot) {
            case 0 -> {
                rocketMode = !rocketMode;
                swapAnim = 0.3f;
                atkTimer = Math.min(atkTimer, 0.15f);
                g.burst(p.x, 1.2f, p.z, 10, rocketMode ? TEAL : PINK, 3);
                return true;
            }
            case 1 -> {
                float dx = p.mouseX - p.x, dz = p.mouseZ - p.z;
                p.face = (float) Math.atan2(dx, dz);
                p.faceLock = 0.6f;
                for (int i = 0; i < 12; i++) g.spark(p.x + (g.rng.nextFloat() - .5f) * 1.2f, 1.2f, p.z + (g.rng.nextFloat() - .5f) * 1.2f, 0x7ff9ff);
                g.later(0.35f, () -> {
                    int n = 1 + zapExtra * 2;
                    for (int i = 0; i < n; i++) {
                        float[] d = dir(dx, dz, spreadOffset(i, 0.18f));
                        Projectile pr = g.shoot(me, ProjKind.ZAP, me.x, me.z, d[0], d[1], 55, zapDmg, 34, 0.7f, 0x7ff9ff);
                        pr.pierce = 999;
                        pr.slow = 0.6f;
                        pr.slowT = 2f;
                        pr.mark = true;
                        pr.y = 1.2f;
                    }
                    g.shake(0.15f);
                });
                return true;
            }
            case 2 -> {
                g.target(me, 12);
                float dx = p.tx - p.x, dz = p.tz - p.z;
                float len = (float) Math.max(0.01, Math.hypot(dx, dz));
                float px = -dz / len, pz = dx / len; // dik yön
                for (int i = 0; i < chompers; i++) {
                    float off = (i - (chompers - 1) / 2f) * 1.9f;
                    Trap t = new Trap();
                    t.x = Game.clampA(p.tx + px * off);
                    t.z = Game.clampA(p.tz + pz * off);
                    t.owner = me;
                    t.arm = 0.6f;
                    t.life = 7f;
                    t.dmg = 50;
                    t.radius = 3f * p.area;
                    t.root = 1.6f;
                    g.traps.add(t);
                    g.burst(t.x, 0.5f, t.z, 6, 0xff8a3a, 3);
                }
                return true;
            }
            case 3 -> {
                float dx = p.mouseX - p.x, dz = p.mouseZ - p.z;
                p.face = (float) Math.atan2(dx, dz);
                p.faceLock = 0.5f;
                Projectile pr = g.shoot(me, ProjKind.MEGA, p.x, p.z, dx, dz, 20, megaDmg, 400, 1.1f, 0xd8344a);
                pr.splash = 6.5f * p.area;
                pr.y = 1.6f;
                g.shake(0.35f);
                g.banner("SÜPER MEGA ÖLÜM ROKETİ!", 1.5f, 0xffff4fa3);
                return true;
            }
        }
        return false;
    }

    @Override
    void onKill(Game g, Enemy e) {
        killCounter++;
        if (killCounter >= exciteKills || e.elite) {
            killCounter = 0;
            boolean was = excitedT > 0;
            excitedT = exciteDur;
            if (!was) g.popText(me.x, 2.6f, me.z, "COŞKU!", 0xffff4fa3, 30);
        }
    }

    @Override
    String status() {
        String s = rocketMode ? "Silah: BALIKSIRTI (roket)" : "Silah: POW-POW (minigun) " + "•".repeat(stacks) + "·".repeat(Math.max(0, maxStacks - stacks));
        if (excitedT > 0) s += "   COŞKU!";
        return s;
    }

    @Override
    int shieldColor() { return 0xff9fd0; }

    @Override
    void visualTick(float dt) {
        super.visualTick(dt);
        lastShot += dt;
        if (swapAnim > 0) swapAnim -= dt;
        if (!rocketMode && lastShot < 0.35f) spin += dt * (14 + stacks * 8);
    }

    @Override
    void writeVis(ByteBuffer b) {
        b.put((byte) (rocketMode ? 1 : 0));
        b.put((byte) stacks);
        b.put((byte) maxStacks);
        b.put((byte) (excitedT > 0 ? 1 : 0));
        b.put(q(lastShot, 100));
        b.put(q(swapAnim, 100));
    }

    @Override
    void readVis(ByteBuffer b) {
        rocketMode = b.get() != 0;
        stacks = u8(b);
        maxStacks = u8(b);
        excitedT = b.get() != 0 ? exciteDur : 0;
        lastShot = u8(b) / 100f;
        swapAnim = u8(b) / 100f;
    }

    @Override
    List<Upgrade> special() {
        return List.of(
                new Upgrade("j_pow", "Pow-Pow Hızlandırıcı", "Minigun +1 hız yükü taşır ve +2 hasar.", 3, 0xffff4fa3, g -> { maxStacks++; minigunDmg += 2; }),
                new Upgrade("j_fish", "Köpekbalığı Füzesi", "Roket patlama alanı +%30, roket hasarı +%20.", 3, 0xff2fb39a, g -> { rocketSplash *= 1.3f; rocketDmg *= 1.2f; }),
                new Upgrade("j_chomp", "Daha Çok Çiğneyici", "Alev Çiğneyiciler +2 tuzak atar.", 3, 0xffff8a3a, g -> chompers += 2),
                new Upgrade("j_zap", "Süper Zap", "Zap! yelpaze şeklinde +2 ışın atar, hasar +%25.", 2, 0xff7ff9ff, g -> { zapExtra++; zapDmg *= 1.25f; }),
                new Upgrade("j_mega", "Mega Ölüm", "Roket bekleme süresi -%25, hasar +%35.", 3, 0xffd8344a, g -> { cdMax[3] *= 0.75f; megaDmg *= 1.35f; }),
                new Upgrade("j_excite", "Coşku Bağımlısı", "Coşku! daha sık tetiklenir ve 1 sn uzun sürer.", 2, 0xffffb3e0, g -> { exciteKills = Math.max(8, exciteKills - 8); exciteDur += 1; })
        );
    }

    // ---------------------------------------------------------------- model
    @Override
    void render(Renderer r, Matrix4f base, float t, boolean moving) {
        float run = moving ? 13 : 0;
        float swing = moving ? (float) Math.sin(t * run) * 0.7f : 0;
        float bob = moving ? Math.abs((float) Math.sin(t * run)) * 0.07f : (float) Math.sin(t * 2.5f) * 0.02f;
        boolean excited = excitedT > 0;

        // bacaklar (çizgili çoraplar)
        for (int s = -1; s <= 1; s += 2) {
            float sw = swing * s;
            r.draw(r.cyl, r.m(base).translate(0.12f * s, 0.78f, 0).rotateX(sw).translate(0, -0.38f, 0).scale(0.13f, 0.76f, 0.13f), 0x2b2140);
            r.draw(r.cyl, r.m(base).translate(0.12f * s, 0.78f, 0).rotateX(sw).translate(0, -0.25f, 0).scale(0.145f, 0.08f, 0.145f), PINK);
            r.draw(r.cyl, r.m(base).translate(0.12f * s, 0.78f, 0).rotateX(sw).translate(0, -0.50f, 0).scale(0.145f, 0.08f, 0.145f), PINK);
            r.draw(r.cube, r.m(base).translate(0.12f * s, 0.78f, 0).rotateX(sw).translate(0, -0.74f, 0.05f).scale(0.17f, 0.1f, 0.28f), 0x3a2230);
        }
        // şort + kemer
        r.draw(r.cube, r.m(base).translate(0, 0.84f + bob, 0).scale(0.4f, 0.2f, 0.26f), 0x3a2a55);
        r.draw(r.cube, r.m(base).translate(0, 0.95f + bob, 0).scale(0.43f, 0.06f, 0.28f), PINK, 0.5f);
        // gövde (ince, koyu üst)
        r.draw(r.cube, r.m(base).translate(0, 1.14f + bob, 0).scale(0.34f, 0.36f, 0.2f), DARK);
        r.draw(r.cube, r.m(base).translate(0, 1.22f + bob, 0.105f).scale(0.2f, 0.12f, 0.01f), PINK, 0.3f);
        // kollar (mavi bulut dövmeli ten)
        r.draw(r.cyl, r.m(base).translate(-0.22f, 1.28f + bob, 0).rotateX(-0.2f - swing * 0.4f).rotateZ(-0.15f).translate(0, -0.24f, 0).scale(0.09f, 0.48f, 0.09f), SKIN);
        r.draw(r.cyl, r.m(base).translate(-0.22f, 1.28f + bob, 0).rotateX(-0.2f - swing * 0.4f).rotateZ(-0.15f).translate(0, -0.18f, 0).scale(0.095f, 0.12f, 0.095f), BLUE);
        r.draw(r.cyl, r.m(base).translate(0.22f, 1.28f + bob, 0).rotateX(-1.25f).translate(0, -0.24f, 0).scale(0.09f, 0.48f, 0.09f), SKIN);
        // kafa
        r.draw(r.sphere, r.m(base).translate(0, 1.56f + bob, 0).scale(0.36f), SKIN);
        r.draw(r.sphere, r.m(base).translate(-0.07f, 1.58f + bob, 0.16f).scale(0.06f), excited ? 0xff3fb4 : 0xff7ac8, 1f);
        r.draw(r.sphere, r.m(base).translate(0.07f, 1.58f + bob, 0.16f).scale(0.06f), excited ? 0xff3fb4 : 0xff7ac8, 1f);
        r.draw(r.cube, r.m(base).translate(0, 1.47f + bob, 0.17f).scale(0.08f, 0.02f, 0.01f), 0xb03060);
        // saç
        r.draw(r.sphere, r.m(base).translate(0, 1.64f + bob, -0.04f).scale(0.42f, 0.34f, 0.42f), BLUE);
        r.draw(r.cube, r.m(base).translate(0, 1.73f + bob, 0.12f).scale(0.36f, 0.08f, 0.14f), BLUE2);
        // uzun örgüler
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < 9; i++) {
                float k = i / 8f;
                float sway = (float) Math.sin(t * 7 + i * 0.6f + s) * 0.06f * k * (moving ? 2f : 1f);
                float x = s * (0.21f + 0.06f * k) + sway;
                float y = 1.52f + bob - i * 0.15f;
                float z = -0.12f - k * (moving ? 0.55f : 0.18f);
                float sc = 0.14f - k * 0.04f;
                r.draw(r.sphere, r.m(base).translate(x, y, z).scale(sc), i % 2 == 0 ? BLUE : BLUE2);
            }
        }
        // silah
        float raise = swapAnim > 0 ? swapAnim * 2f : 0;
        if (!rocketMode) {
            // Pow-Pow: kalçada tutulan minigun
            float gy = 1.02f + bob - raise;
            r.draw(r.cyl, r.m(base).translate(0.3f, gy, 0.25f).rotateX(HALF_PI).scale(0.26f, 0.55f, 0.26f), GUN);
            r.draw(r.cyl, r.m(base).translate(0.3f, gy, 0.38f).rotateX(HALF_PI).scale(0.29f, 0.07f, 0.29f), PINK, 0.4f);
            r.draw(r.cube, r.m(base).translate(0.3f, gy - 0.2f, 0.15f).scale(0.08f, 0.22f, 0.1f), 0x30343e);
            for (int i = 0; i < 4; i++) {
                double a = spin + i * Math.PI / 2;
                float ox = (float) Math.cos(a) * 0.075f, oy = (float) Math.sin(a) * 0.075f;
                r.draw(r.cyl, r.m(base).translate(0.3f + ox, gy + oy, 0.78f).rotateX(HALF_PI).scale(0.06f, 0.55f, 0.06f), GRAY);
            }
            if (lastShot < 0.06f)
                r.draw(r.sphere, r.m(base).translate(0.3f, gy, 1.1f).scale(0.28f), 0xffd07a, 1f);
        } else {
            // Balıksırtı: omuzda köpekbalığı roketatar
            float gy = 1.42f + bob - raise;
            r.draw(r.cyl, r.m(base).translate(0.3f, gy, 0.05f).rotateX(HALF_PI).scale(0.36f, 1.2f, 0.36f), TEAL);
            r.draw(r.sphere, r.m(base).translate(0.3f, gy, 0.66f).scale(0.42f, 0.38f, 0.5f), TEAL);
            r.draw(r.cube, r.m(base).translate(0.3f, gy - 0.07f, 0.86f).scale(0.3f, 0.1f, 0.14f), 0x40101a);
            for (int i = 0; i < 4; i++)
                r.draw(r.cone, r.m(base).translate(0.19f + i * 0.075f, gy - 0.02f, 0.88f).rotateX((float) Math.PI).scale(0.05f, 0.08f, 0.05f), 0xffffff);
            r.draw(r.sphere, r.m(base).translate(0.12f, gy + 0.12f, 0.75f).scale(0.09f), 0xff2a2a, 1f);
            r.draw(r.sphere, r.m(base).translate(0.48f, gy + 0.12f, 0.75f).scale(0.09f), 0xff2a2a, 1f);
            r.draw(r.cube, r.m(base).translate(0.3f, gy + 0.28f, 0.0f).rotateX(-0.4f).scale(0.06f, 0.32f, 0.26f), TEAL2);
            r.draw(r.cube, r.m(base).translate(0.3f, gy + 0.15f, -0.62f).rotateX(0.6f).scale(0.06f, 0.32f, 0.18f), TEAL2);
            r.draw(r.cube, r.m(base).translate(0.3f, gy - 0.15f, -0.62f).rotateX(-0.6f).scale(0.06f, 0.32f, 0.18f), TEAL2);
            r.draw(r.cube, r.m(base).translate(0.3f, gy, -0.2f).scale(0.4f, 0.1f, 0.12f), PINK, 0.3f);
        }
    }
}
