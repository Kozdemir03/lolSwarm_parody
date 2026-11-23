package swarm;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

import java.util.List;

/** Lux benzeri: ışık küresi, bağlayıcı ışık, parlak alan ve devasa son ışın. Yetenekler düşmanı işaretler. */
final class Lumina extends Champion {
    static final int WHITE = 0xf3f1ea, GOLD = 0xe8c25a, BLUE = 0x4a78d8, HAIR = 0xf2d27a, SKIN = 0xf4d6c6, LIGHT = 0xfff1a8;

    float orbDmg = 20, illum = 30, rootDur = 2f, zoneR = 3.5f, zoneDmg = 75, beamW = 2.6f, beamDmg = 320, castAnim;
    int bindTargets = 2;

    Lumina() {
        super("Lumina", "Işığın Kızı",
                "Uzaktan savaşan ışık büyücüsü. Yetenekleri düşmanları işaretler; işaretli düşmana temel saldırı ekstra patlar.",
                0xfff1a8);
        abil[0] = "Işık Bağı";
        abilDesc[0] = "İlk 2 düşmanı delip geçen ve onları köklendiren ışık küresi.";
        abil[1] = "Prizma Kalkanı";
        abilDesc[1] = "Seni koruyan ışık kalkanı.";
        abil[2] = "Parlak Alan";
        abilDesc[2] = "Fare noktasında yavaşlatan ışık alanı; kısa süre sonra patlar.";
        abil[3] = "Son Kıvılcım";
        abilDesc[3] = "Fare yönüne, yoldaki her şeyi yakan devasa ışık lazeri.";
        cdMax[0] = 7f; cdMax[1] = 12f; cdMax[2] = 8f; cdMax[3] = 26f;
        baseHp = 90;
        baseSpeed = 6.5f;
    }

    @Override
    void tick(Game g, float dt) {
        Player p = me;
        if (castAnim > 0) castAnim -= dt;
        atkTimer -= dt * p.asMult;
        if (atkTimer <= 0) {
            if (g.aim(me, 15)) {
                float dx = p.aimX - p.x, dz = p.aimZ - p.z;
                for (int i = 0; i < 1 + p.projBonus; i++) {
                    float[] d = dir(dx, dz, spreadOffset(i, 0.16f));
                    Projectile pr = g.shoot(me, ProjKind.ORB, p.x, p.z, d[0], d[1], 19, orbDmg, 16, 0.4f, LIGHT);
                    pr.markBonus = illum;
                    pr.y = 1.3f;
                }
                castAnim = 0.2f;
                atkTimer = 1f / 1.35f;
            } else atkTimer = 0;
        }
    }

    @Override
    boolean cast(Game g, int slot) {
        Player p = me;
        float dx = p.mouseX - p.x, dz = p.mouseZ - p.z;
        switch (slot) {
            case 0 -> {
                p.face = (float) Math.atan2(dx, dz);
                p.faceLock = 0.4f;
                Projectile pr = g.shoot(me, ProjKind.BIND, p.x, p.z, dx, dz, 24, 55, 18, 0.6f, 0x9ff0ff);
                pr.pierce = bindTargets - 1;
                pr.root = rootDur;
                pr.mark = true;
                pr.y = 1.2f;
                castAnim = 0.3f;
                return true;
            }
            case 1 -> {
                p.shield = 70 + p.maxHp * 0.15f;
                p.shieldT = 2.5f;
                g.ringFx(p.x, p.z, 0.5f, 3f, 0.4f, 0xffb0e0);
                g.burst(p.x, 1.2f, p.z, 12, 0xffb0e0, 3);
                return true;
            }
            case 2 -> {
                g.target(me, 14);
                Zone zn = new Zone();
                zn.x = p.tx;
                zn.z = p.tz;
                zn.radius = zoneR * p.area;
                zn.life = zn.max = 2.0f;
                zn.slow = 0.5f;
                zn.color = LIGHT;
                final float zx = zn.x, zz = zn.z, zr = zn.radius;
                zn.onEnd = () -> {
                    g.aoe(me, zx, zz, zr, zoneDmg, e -> e.markT = 4f);
                    g.explosion(zx, zz, zr, LIGHT);
                };
                g.zones.add(zn);
                castAnim = 0.3f;
                return true;
            }
            case 3 -> {
                float len = (float) Math.max(0.01, Math.hypot(dx, dz));
                float nx = dx / len, nz = dz / len;
                p.face = (float) Math.atan2(nx, nz);
                p.faceLock = 1.0f;
                Beam b = new Beam();
                b.x1 = p.x + nx;
                b.z1 = p.z + nz;
                b.x2 = p.x + nx * 36;
                b.z2 = p.z + nz * 36;
                b.width = beamW * p.area;
                b.delay = 0.55f;
                b.life = b.max = 0.45f;
                b.dmg = beamDmg;
                b.color = LIGHT;
                b.mark = true;
                b.owner = me;
                g.beams.add(b);
                castAnim = 1.0f;
                return true;
            }
        }
        return false;
    }

    @Override
    int shieldColor() { return 0xffb0e0; }

    @Override
    void visualTick(float dt) {
        super.visualTick(dt);
        if (castAnim > 0) castAnim -= dt;
    }

    @Override
    void writeVis(ByteBuffer b) { b.put(q(castAnim, 100)); }

    @Override
    void readVis(ByteBuffer b) { castAnim = u8(b) / 100f; }

    @Override
    List<Upgrade> special() {
        return List.of(
                new Upgrade("l_orb", "Prizmatik Kırılma", "Işık küresi hasarı +%25.", 3, 0xfffff1a8, g -> orbDmg *= 1.25f),
                new Upgrade("l_bind", "Güçlü Bağ", "Işık Bağı +2 hedefi deler, kök süresi +0.5 sn.", 2, 0xff9ff0ff, g -> { bindTargets += 2; rootDur += 0.5f; }),
                new Upgrade("l_zone", "Parlayan Alan", "Parlak Alan +%25 büyük ve +%30 hasarlı.", 3, 0xffe8c25a, g -> { zoneR *= 1.25f; zoneDmg *= 1.3f; }),
                new Upgrade("l_ult", "Final Işını", "Son Kıvılcım bekleme -%25, genişlik +%30.", 3, 0xffffffff, g -> { cdMax[3] *= 0.75f; beamW *= 1.3f; beamDmg *= 1.15f; }),
                new Upgrade("l_illum", "Aydınlanma", "İşaretli düşmana bonus hasar +%60.", 2, 0xffffe08a, g -> illum *= 1.6f)
        );
    }

    // ---------------------------------------------------------------- model
    @Override
    void render(Renderer r, Matrix4f base, float t, boolean moving) {
        float bob = (float) Math.sin(t * (moving ? 10 : 2.5f)) * (moving ? 0.05f : 0.025f);
        // elbise
        r.draw(r.cone, r.m(base).translate(0, 0.62f + bob * 0.5f, 0).scale(0.8f, 1.24f, 0.8f), WHITE);
        r.draw(r.cyl, r.m(base).translate(0, 0.06f, 0).scale(0.82f, 0.08f, 0.82f), GOLD);
        r.draw(r.cyl, r.m(base).translate(0, 0.45f, 0).scale(0.65f, 0.06f, 0.65f), BLUE);
        // gövde
        r.draw(r.cube, r.m(base).translate(0, 1.18f + bob, 0).scale(0.36f, 0.38f, 0.22f), WHITE);
        r.draw(r.cube, r.m(base).translate(0, 1.0f + bob, 0).scale(0.4f, 0.07f, 0.26f), GOLD);
        r.draw(r.cube, r.m(base).translate(0, 1.22f + bob, 0.115f).scale(0.12f, 0.12f, 0.01f), BLUE, 0.4f);
        // kollar
        r.draw(r.cyl, r.m(base).translate(-0.22f, 1.3f + bob, 0).rotateX(-0.2f).rotateZ(-0.2f).translate(0, -0.22f, 0).scale(0.085f, 0.46f, 0.085f), SKIN);
        float armA = castAnim > 0 ? -1.6f : -1.0f;
        r.draw(r.cyl, r.m(base).translate(0.22f, 1.3f + bob, 0).rotateX(armA).translate(0, -0.22f, 0).scale(0.085f, 0.46f, 0.085f), SKIN);
        // asa
        Matrix4f w = r.m(base).translate(0.26f, 1.3f + bob, 0).rotateX(armA).translate(0, -0.45f, 0).rotateX(HALF_PI * 0.9f);
        Matrix4f save = new Matrix4f(w);
        r.draw(r.cyl, w.translate(0, 0.3f, 0).scale(0.05f, 0.75f, 0.05f), GOLD);
        r.draw(r.sphere, r.m(save).translate(0, 0.72f, 0).scale(0.2f + (castAnim > 0 ? 0.12f : 0f)), LIGHT, 1f);
        r.draw(r.ring, r.m(save).translate(0, 0.72f, 0).scale(0.4f), GOLD, 0.5f);
        // kafa + saç
        r.draw(r.sphere, r.m(base).translate(0, 1.58f + bob, 0).scale(0.36f), SKIN);
        r.draw(r.sphere, r.m(base).translate(-0.07f, 1.6f + bob, 0.16f).scale(0.055f), 0x3a7af0, 0.6f);
        r.draw(r.sphere, r.m(base).translate(0.07f, 1.6f + bob, 0.16f).scale(0.055f), 0x3a7af0, 0.6f);
        r.draw(r.sphere, r.m(base).translate(0, 1.66f + bob, -0.05f).scale(0.42f, 0.36f, 0.42f), HAIR);
        r.draw(r.cone, r.m(base).translate(0, 1.4f + bob, -0.24f).rotateX(0.25f).rotateX((float) Math.PI).scale(0.26f, 0.7f, 0.2f), HAIR);
        r.draw(r.cube, r.m(base).translate(0, 1.8f + bob, 0.08f).scale(0.34f, 0.05f, 0.1f), GOLD, 0.3f);
        r.draw(r.sphere, r.m(base).translate(0, 1.82f + bob, 0.14f).scale(0.07f), 0x6fb4ff, 1f);
        // etrafta süzülen ışık zerreleri
        for (int i = 0; i < 3; i++) {
            double a = t * 2 + i * Math.PI * 2 / 3;
            r.draw(r.sphere, r.m(base).translate((float) Math.cos(a) * 0.75f, 1.2f + (float) Math.sin(t * 3 + i) * 0.25f, (float) Math.sin(a) * 0.75f).scale(0.1f), LIGHT, 1f);
        }
    }
}
