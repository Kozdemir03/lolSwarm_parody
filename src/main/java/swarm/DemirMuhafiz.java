package swarm;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

import java.util.List;

/** Garen benzeri: yakın dövüş kılıç, güçlendirilmiş darbe, kalkan, dönen yargı ve gökten düşen dev kılıç. */
final class DemirMuhafiz extends Champion {
    static final int SILVER = 0xb8c2cc, BLUE = 0x2a4fa8, GOLD = 0xe0b23a, SKIN = 0xe9c3a6, STEEL = 0xd8e0ea;

    float spinT, spinTick, spinAngle, swingAnim, empT;
    boolean empowered;
    float spinDur = 3f, spinRadius = 3.2f, spinDmg = 15, atkDmg = 30, atkRange = 3.8f, execPct = 0.3f, shieldAmt = 60, regenMult = 1;
    private final Matrix4f b2 = new Matrix4f();

    DemirMuhafiz() {
        super("Demir Muhafız", "Krallığın Kılıcı",
                "Ağır zırhlı, dev kılıçlı ön saf savaşçısı. Sürünün ortasına dal, dön ve hepsini biç!",
                0x4a78d8);
        abil[0] = "Kararlı Darbe";
        abilDesc[0] = "3 sn hızlanırsın; sonraki kılıç darben 2.5x hasar verir ve düşmanları sersemletir.";
        abil[1] = "Cesaret";
        abilDesc[1] = "3 sn boyunca hasar emen kalkan ve %30 hasar azaltma.";
        abil[2] = "Yargı";
        abilDesc[2] = "Kılıcınla 3 sn boyunca döner, etrafındaki herkese sürekli hasar verirsin.";
        abil[3] = "Kraliyet Adaleti";
        abilDesc[3] = "Fare noktasına gökten dev kılıç düşer. Büyük alan hasarı; canı %30'un altındaki düşmanları infaz eder.";
        cdMax[0] = 7f; cdMax[1] = 14f; cdMax[2] = 9f; cdMax[3] = 35f;
        baseHp = 150;
        baseSpeed = 6.2f;
    }

    @Override
    void tick(Game g, float dt) {
        Player p = me;
        // Pasif: Kararlılık — 4 sn hasar almazsan hızlı can yenilenmesi
        if (g.time - p.lastHit > 4f) p.heal(p.maxHp * 0.02f * regenMult * dt);

        if (empT > 0) empT -= dt;
        else empowered = false;
        p.buffSpeed = empT > 0 ? 0.35f : (spinT > 0 ? 0.1f : 0f);
        if (swingAnim > 0) swingAnim -= dt;

        if (spinT > 0) {
            spinT -= dt;
            spinAngle += dt * 20;
            spinTick -= dt * (0.7f + 0.3f * p.asMult);
            if (spinTick <= 0) {
                spinTick = 0.22f;
                float rad = spinRadius * p.area;
                g.aoe(me, p.x, p.z, rad, spinDmg, e -> g.knock(e, p.x, p.z, 3));
                for (int i = 0; i < 6; i++) {
                    double a = g.rng.nextDouble() * Math.PI * 2;
                    g.spark(p.x + (float) Math.cos(a) * rad * 0.8f, 1.0f, p.z + (float) Math.sin(a) * rad * 0.8f, i % 2 == 0 ? STEEL : GOLD);
                }
            }
            return; // dönerken normal saldırı yok
        }
        spinAngle = 0;

        atkTimer -= dt * p.asMult;
        if (atkTimer <= 0) {
            if (g.aim(me, atkRange * p.area + 0.6f)) {
                swing(g);
                atkTimer = 1f / 1.2f;
            } else atkTimer = 0;
        }
    }

    private void swing(Game g) {
        Player p = me;
        float ang = (float) Math.atan2(p.aimX - p.x, p.aimZ - p.z);
        float range = atkRange * p.area;
        float dmg = atkDmg * (empowered ? 2.5f : 1f);
        boolean emp = empowered;
        float halfArc = (float) Math.toRadians(70 + 15 * p.projBonus);
        swingAnim = 0.25f;
        g.near(p.x, p.z, range + 3, g.tmpBuf);
        for (Enemy e : g.tmpBuf) {
            if (e.dead) continue;
            float dx = e.x - p.x, dz = e.z - p.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > range + e.radius) continue;
            float a = (float) Math.atan2(dx, dz) - ang;
            while (a > Math.PI) a -= (float) (2 * Math.PI);
            while (a < -Math.PI) a += (float) (2 * Math.PI);
            if (Math.abs(a) > halfArc && d > e.radius + 0.6f) continue;
            g.damage(me, e, dmg, true);
            g.knock(e, p.x, p.z, emp ? 9 : 4);
            if (emp) e.rootT = Math.max(e.rootT, 1.2f);
        }
        // yay şeklinde kıvılcım efekti
        for (int i = 0; i <= 10; i++) {
            float a = ang - halfArc + 2 * halfArc * i / 10f;
            float sx = p.x + (float) Math.sin(a) * range * 0.85f, sz = p.z + (float) Math.cos(a) * range * 0.85f;
            g.spark(sx, 1.0f, sz, emp ? GOLD : STEEL);
        }
        if (emp) {
            g.shake(0.2f);
            g.ringFx(p.x, p.z, 0.5f, range, 0.25f, GOLD);
            empowered = false;
            empT = Math.min(empT, 0.5f);
        }
    }

    @Override
    boolean cast(Game g, int slot) {
        Player p = me;
        switch (slot) {
            case 0 -> {
                empowered = true;
                empT = 3f;
                atkTimer = Math.min(atkTimer, 0.1f);
                g.burst(p.x, 1.3f, p.z, 10, GOLD, 3);
                return true;
            }
            case 1 -> {
                p.shield = shieldAmt + p.maxHp * 0.1f;
                p.shieldT = 3f;
                p.dr = 0.3f;
                p.drT = 3f;
                g.ringFx(p.x, p.z, 0.5f, 2.5f, 0.4f, GOLD);
                return true;
            }
            case 2 -> {
                if (spinT > 0) return false;
                spinT = spinDur;
                spinTick = 0;
                return true;
            }
            case 3 -> {
                g.target(me, 14);
                final float x = p.tx, z = p.tz;
                FallingSword s = new FallingSword();
                s.x = x;
                s.z = z;
                s.t = s.max = 0.45f;
                s.stuck = 1.0f;
                g.swords.add(s);
                g.telegraph(x, z, 4.5f * p.area, 0.45f, 0xe0b23a, () -> {
                    float rad = 4.5f * me.area;
                    g.aoe(me, x, z, rad, 220, e -> {
                        if (!e.dead && e.kind != EnemyKind.BOSS && e.hp < e.maxHp * execPct) g.execute(me, e);
                        else g.knock(e, x, z, 10);
                    });
                    g.explosion(x, z, rad, GOLD);
                    g.shake(0.5f);
                });
                return true;
            }
        }
        return false;
    }

    @Override
    String status() {
        if (spinT > 0) return "YARGI!  " + String.format("%.1f", spinT) + " sn";
        if (empowered) return "Kararlı Darbe hazır!";
        return null;
    }

    @Override
    int shieldColor() { return 0xffd86a; }

    @Override
    void visualTick(float dt) {
        super.visualTick(dt);
        if (spinT > 0) { spinT -= dt; spinAngle += dt * 20; } else spinAngle = 0;
        if (swingAnim > 0) swingAnim -= dt;
    }

    @Override
    void writeVis(ByteBuffer b) {
        b.put(q(spinT, 20));
        b.put(q(swingAnim, 400));
        b.put((byte) (empowered ? 1 : 0));
    }

    @Override
    void readVis(ByteBuffer b) {
        spinT = u8(b) / 20f;
        swingAnim = u8(b) / 400f;
        empowered = b.get() != 0;
    }

    @Override
    List<Upgrade> special() {
        return List.of(
                new Upgrade("g_spin", "Dönen Yargı", "Yargı 1 sn daha uzun sürer, alanı +%15, hasarı +%20.", 3, 0xffd8e0ea, g -> { spinDur += 1; spinRadius *= 1.15f; spinDmg *= 1.2f; }),
                new Upgrade("g_blade", "Keskin Kılıç", "Kılıç darbesi hasarı +%25, menzil +%10.", 3, 0xffb8c2cc, g -> { atkDmg *= 1.25f; atkRange *= 1.1f; }),
                new Upgrade("g_shield", "Kalın Zırh", "Cesaret kalkanı +50 ve bekleme süresi -%20.", 2, 0xffe0b23a, g -> { shieldAmt += 50; cdMax[1] *= 0.8f; }),
                new Upgrade("g_ult", "Krallık Adaleti", "İnfaz eşiği +%10, Kraliyet Adaleti bekleme -%20.", 2, 0xff4a78d8, g -> { execPct += 0.1f; cdMax[3] *= 0.8f; }),
                new Upgrade("g_regen", "Sarsılmaz", "Pasif can yenilenmesi 2 katına çıkar.", 1, 0xff5ad17a, g -> regenMult *= 2)
        );
    }

    // ---------------------------------------------------------------- model
    @Override
    void render(Renderer r, Matrix4f base, float t, boolean moving) {
        Matrix4f b = b2.set(base).rotateY(spinAngle);
        float run = moving ? 11 : 0;
        float swing = moving ? (float) Math.sin(t * run) * 0.6f : 0;
        float bob = moving ? Math.abs((float) Math.sin(t * run)) * 0.06f : (float) Math.sin(t * 2) * 0.015f;

        for (int s = -1; s <= 1; s += 2) {
            float sw = swing * s;
            r.draw(r.cyl, r.m(b).translate(0.18f * s, 0.85f, 0).rotateX(sw).translate(0, -0.4f, 0).scale(0.2f, 0.82f, 0.2f), SILVER);
            r.draw(r.cube, r.m(b).translate(0.18f * s, 0.85f, 0).rotateX(sw).translate(0, -0.8f, 0.06f).scale(0.24f, 0.14f, 0.36f), 0x5c6470);
        }
        // tunik + gövde
        r.draw(r.cube, r.m(b).translate(0, 0.95f + bob, 0).scale(0.6f, 0.35f, 0.38f), BLUE);
        r.draw(r.cube, r.m(b).translate(0, 1.35f + bob, 0).scale(0.66f, 0.55f, 0.42f), SILVER);
        r.draw(r.cube, r.m(b).translate(0, 1.38f + bob, 0.215f).scale(0.3f, 0.36f, 0.02f), BLUE);
        r.draw(r.cube, r.m(b).translate(0, 1.4f + bob, 0.226f).scale(0.12f, 0.2f, 0.01f), GOLD, 0.3f);
        r.draw(r.cube, r.m(b).translate(0, 1.08f + bob, 0).scale(0.64f, 0.07f, 0.42f), GOLD);
        // pelerin
        float capeA = 0.12f + (moving ? 0.35f : 0) + (spinT > 0 ? 0.8f : 0);
        r.draw(r.cube, r.m(b).translate(0, 1.6f + bob, -0.24f).rotateX(capeA).translate(0, -0.55f, 0).scale(0.62f, 1.1f, 0.05f), BLUE);
        // omuzluklar
        for (int s = -1; s <= 1; s += 2) {
            r.draw(r.sphere, r.m(b).translate(0.42f * s, 1.58f + bob, 0).scale(0.42f, 0.32f, 0.42f), SILVER);
            r.draw(r.sphere, r.m(b).translate(0.42f * s, 1.66f + bob, 0).scale(0.3f, 0.12f, 0.3f), GOLD);
            r.draw(r.cyl, r.m(b).translate(0.42f * s, 1.48f + bob, 0).rotateX(s < 0 ? -0.3f - swing * 0.3f : -1.0f).translate(0, -0.25f, 0).scale(0.15f, 0.5f, 0.15f), SILVER);
        }
        // miğfer
        r.draw(r.sphere, r.m(b).translate(0, 1.88f + bob, 0).scale(0.4f, 0.44f, 0.4f), SKIN);
        r.draw(r.sphere, r.m(b).translate(0, 1.95f + bob, -0.02f).scale(0.44f, 0.4f, 0.44f), SILVER);
        r.draw(r.cube, r.m(b).translate(0, 1.88f + bob, 0.18f).scale(0.3f, 0.06f, 0.06f), 0x202630);
        r.draw(r.cube, r.m(b).translate(0, 2.17f + bob, -0.03f).scale(0.06f, 0.14f, 0.36f), GOLD);

        // dev kılıç
        Matrix4f sw;
        if (spinT > 0) {
            sw = r.m(b).translate(0.55f, 1.2f, 0.2f).rotateZ(-HALF_PI).rotateY(0.2f);
        } else if (swingAnim > 0) {
            float k = swingAnim / 0.25f; // 1 -> 0
            sw = r.m(b).translate(0.45f, 1.3f + bob, 0.3f).rotateY(-1.3f + 2.4f * (1 - k)).rotateX(1.3f);
        } else {
            sw = r.m(b).translate(0.45f, 1.1f + bob, 0.3f).rotateX(0.35f);
        }
        Matrix4f save = new Matrix4f(sw);
        r.draw(r.cube, sw.translate(0, 0.95f, 0).scale(0.15f, 1.7f, 0.05f), STEEL, empowered ? 0.6f : 0.15f);
        r.draw(r.cube, r.m(save).translate(0, 0.05f, 0).scale(0.55f, 0.09f, 0.12f), GOLD);
        r.draw(r.cyl, r.m(save).translate(0, -0.2f, 0).scale(0.08f, 0.4f, 0.08f), 0x4a3020);
        r.draw(r.sphere, r.m(save).translate(0, -0.42f, 0).scale(0.13f), GOLD);
    }
}
