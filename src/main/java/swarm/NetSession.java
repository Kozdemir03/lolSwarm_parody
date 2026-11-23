package swarm;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Host-otoriter co-op protokolü (UDP).
 * <ul>
 *   <li>İstemci → host: INPUT (her karede; onaylanmamış hareket girdileri tekrarlı, yetenek sayaçları, seviye seçimi)</li>
 *   <li>Host → istemci: HEAD (oyun durumu + oyuncular, 20 Hz), CHUNK (düşmanlar, mermiler ...), EVENTS (efektler)</li>
 * </ul>
 * Kayıp paketlere dayanıklı: yetenekler sayaçla, seçimler tur numarasıyla, hareket sıra numarasıyla gönderilir.
 */
final class NetSession {
    static final short MAGIC = 0x5253; // "RS"
    static final byte VER = 1;
    static final byte T_PUNCH = 1, T_HELLO = 2, T_WELCOME = 3, T_INPUT = 4, T_HEAD = 5, T_CHUNK = 6, T_EVENTS = 7, T_BYE = 8;
    static final int MTU = 1200, CATS = 8;
    static final int C_ENEMY = 0, C_PROJ = 1, C_ORB = 2, C_TRAP = 3, C_ZONE = 4, C_BEAM = 5, C_TELE = 6, C_SWORD = 7;
    static final float TIMEOUT = 8f, CULL = 60f;

    final Game g;
    final Net net;
    final boolean host;
    String status = "";
    private final ByteBuffer out = ByteBuffer.allocate(1600);
    private final Random rng = new Random();

    // ------------------------------------------------------------------ host durumu
    static final class Peer {
        InetSocketAddress addr;
        int id, lastSeq = -1;
        float sinceHeard;
        final int[] castSeen = new int[4];
        boolean castInit;
    }

    final ArrayList<Peer> peers = new ArrayList<>();
    final ArrayList<InetSocketAddress> punchTargets = new ArrayList<>();
    private float punchAcc, snapAcc;
    private int snapId;
    private boolean orbTick;

    // ------------------------------------------------------------------ istemci durumu
    InetSocketAddress hostAddr;
    int myId = -1;
    boolean connected;
    private int nonce;
    private float helloAcc, sinceHeard, connectT, keepAcc;
    private final int[] castCount = new int[4];
    private int seq;
    private final ArrayDeque<float[]> history = new ArrayDeque<>(); // {seq, mx, mz, dt}
    private int myPickRound = -1, myPickChoice = -1, curGameId = -1, lastHeadSnap = -1;
    private float serverSpeed = 6.5f;
    private final Asm[] asm = new Asm[CATS];
    private final int[] applied = new int[CATS];
    private final HashMap<Integer, Enemy> enemyMap = new HashMap<>();
    private final HashMap<Integer, Projectile> projMap = new HashMap<>();

    private static final class Asm {
        int snap, got;
        byte[][] parts;
    }

    private NetSession(Game g, Net net, boolean host) {
        this.g = g;
        this.net = net;
        this.host = host;
        Arrays.fill(applied, -1);
    }

    static NetSession host(Game g) throws SocketException {
        Net n = new Net(Net.DEFAULT_PORT);
        n.stunAsync();
        NetSession s = new NetSession(g, n, true);
        s.status = "Oyuncular bekleniyor...";
        return s;
    }

    static NetSession client(Game g) throws SocketException {
        Net n = new Net(Net.DEFAULT_PORT + 1);
        n.stunAsync();
        NetSession s = new NetSession(g, n, false);
        s.status = "Host'un kodunu yapıştır ve ENTER'a bas.";
        return s;
    }

    String myCode() { return net.publicAddr != null ? Net.encode(net.publicAddr) : null; }

    String publicStr() {
        InetSocketAddress a = net.publicAddr;
        return a != null ? a.getAddress().getHostAddress() + ":" + a.getPort() : "?";
    }

    String localStr() { return Net.localIp() + ":" + net.port(); }

    boolean hasPeers() { return host && !peers.isEmpty(); }

    void submitCode(String s) {
        try {
            InetSocketAddress a = Net.decode(s);
            if (host) {
                if (!punchTargets.contains(a)) punchTargets.add(a);
                status = "Bağlantı açılıyor → " + a.getAddress().getHostAddress() + ":" + a.getPort() + "  (arkadaşın da senin kodunu girmeli)";
            } else {
                hostAddr = a;
                nonce = rng.nextInt();
                connected = false;
                connectT = 0;
                helloAcc = 1;
                status = "Bağlanıyor → " + a.getAddress().getHostAddress() + ":" + a.getPort() + " ...";
            }
        } catch (IllegalArgumentException e) {
            status = "Kod hatalı: " + e.getMessage();
        }
    }

    void close() {
        ByteBuffer o = begin(T_BYE);
        str(o, "ayrıldı");
        if (host) for (Peer p : peers) sendTo(p.addr);
        else if (hostAddr != null) sendTo(hostAddr);
        net.close();
    }

    // ================================================================== alma
    void poll(float dt) {
        Net.Packet p;
        while ((p = net.inbox.poll()) != null) {
            ByteBuffer b = ByteBuffer.wrap(p.data());
            if (b.remaining() < 3 || b.getShort() != MAGIC) continue;
            byte t = b.get();
            try {
                if (host) hostRecv(t, b, p.from());
                else clientRecv(t, b, p.from());
            } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
                // bozuk/kısa paket: yok say
            }
            if (g.net != this) return; // oturum kapandı
        }
        if (host) {
            for (int i = peers.size() - 1; i >= 0; i--) {
                Peer pe = peers.get(i);
                pe.sinceHeard += dt;
                if (pe.sinceHeard > TIMEOUT) dropPeer(pe, "bağlantısı koptu");
            }
        } else if (connected) {
            sinceHeard += dt;
            if (sinceHeard > TIMEOUT) g.leaveToMenu("Host ile bağlantı koptu.");
        } else if (hostAddr != null) {
            connectT += dt;
            if (connectT > 30) {
                status = "Bağlanılamadı. Host da senin kodunu girdi mi? Olmazsa host modemde UDP " + Net.DEFAULT_PORT + " portunu yönlendirmeli.";
                hostAddr = null;
            }
        }
    }

    private void hostRecv(byte t, ByteBuffer b, InetSocketAddress from) {
        switch (t) {
            case T_HELLO -> {
                int ver = b.get();
                int champ = b.get() & 0xff;
                int nn = b.getInt();
                if (ver != VER) { bye(from, "Sürüm farklı, ikiniz de aynı jar'ı kullanın"); return; }
                Peer pe = peer(from);
                if (pe == null) {
                    if (g.state != Game.State.LOBBY) { bye(from, "Oyun zaten başladı"); return; }
                    int id = freeId();
                    if (id < 0) { bye(from, "Oda dolu (en fazla 4 oyuncu)"); return; }
                    pe = new Peer();
                    pe.addr = from;
                    pe.id = id;
                    peers.add(pe);
                    g.players.add(g.makePlayer(id, Math.min(2, champ), false));
                    g.players.sort(Comparator.comparingInt(q -> q.id));
                    g.banner("P" + (id + 1) + " katıldı!", 2f, 0xff7fe0ff);
                    status = peers.size() + " oyuncu bağlı. ENTER (kutu boşken) ile başlat.";
                }
                pe.sinceHeard = 0;
                ByteBuffer o = begin(T_WELCOME);
                o.putInt(nn);
                o.put((byte) pe.id);
                sendTo(from);
            }
            case T_INPUT -> {
                Peer pe = peer(from);
                if (pe != null) {
                    pe.sinceHeard = 0;
                    readInput(pe, b);
                }
            }
            case T_BYE -> {
                Peer pe = peer(from);
                if (pe != null) dropPeer(pe, "ayrıldı");
            }
            default -> { }
        }
    }

    private void readInput(Peer pe, ByteBuffer b) {
        b.get(); // id
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) c[i] = b.get() & 0xff;
        boolean lmb = b.get() != 0;
        float mx = b.getFloat(), mz = b.getFloat();
        int round = b.getShort();
        int choice = b.get();
        int n = b.get() & 0xff;
        Player p = g.find(pe.id);
        if (p == null) return;
        p.lmb = lmb;
        if (Float.isFinite(mx) && Float.isFinite(mz)) { p.mouseX = mx; p.mouseZ = mz; }
        if (!pe.castInit) {
            System.arraycopy(c, 0, pe.castSeen, 0, 4);
            pe.castInit = true;
        }
        for (int i = 0; i < 4; i++) {
            if (((c[i] - pe.castSeen[i]) & 0xff) != 0) {
                if (g.state == Game.State.PLAY) p.castReq[i] = true;
                pe.castSeen[i] = c[i];
            }
        }
        if (choice >= 0 && g.state == Game.State.LEVELUP && round == g.pickRound) g.choose(p, choice);
        for (int k = 0; k < n; k++) {
            int s = b.getInt();
            float ix = b.get() / 100f, iz = b.get() / 100f, idt = (b.get() & 0xff) / 1000f;
            if (s > pe.lastSeq) {
                pe.lastSeq = s;
                if (g.state == Game.State.PLAY && !p.downed && idt > 0)
                    p.moveQueue.add(new float[]{ix, iz, Math.min(idt, 0.1f)});
            }
        }
    }

    private void clientRecv(byte t, ByteBuffer b, InetSocketAddress from) {
        switch (t) {
            case T_WELCOME -> {
                int nn = b.getInt();
                int id = b.get();
                if (!connected && nn == nonce) {
                    connected = true;
                    myId = id;
                    hostAddr = from; // NAT farklı port verdiyse ona uy
                    sinceHeard = 0;
                    status = "Bağlandı! Host'un oyunu başlatması bekleniyor.";
                }
            }
            case T_HEAD -> { if (connected) { sinceHeard = 0; readHead(b); } }
            case T_CHUNK -> { if (connected) readChunk(b); }
            case T_EVENTS -> { if (connected) readEvents(b); }
            case T_BYE -> {
                String why = b.remaining() > 0 ? str(b) : "";
                if (connected) g.leaveToMenu("Host bağlantıyı kapattı " + why);
                else { status = "Host reddetti: " + why; hostAddr = null; }
            }
            default -> { }
        }
    }

    private void dropPeer(Peer pe, String why) {
        peers.remove(pe);
        g.removePlayer(pe.id);
        g.banner("P" + (pe.id + 1) + " " + why, 2.5f, 0xffff7a5a);
        status = peers.size() + " oyuncu bağlı.";
    }

    private void bye(InetSocketAddress to, String why) {
        ByteBuffer o = begin(T_BYE);
        str(o, why);
        sendTo(to);
    }

    private Peer peer(InetSocketAddress a) {
        for (Peer p : peers) if (p.addr.equals(a)) return p;
        return null;
    }

    private Peer peerById(int id) {
        for (Peer p : peers) if (p.id == id) return p;
        return null;
    }

    private int freeId() {
        for (int id = 1; id < 4; id++) if (g.find(id) == null) return id;
        return -1;
    }

    // ================================================================== gönderme
    void flush(float dt) {
        if (host) {
            punchAcc += dt;
            if (punchAcc >= 0.3f) {
                punchAcc = 0;
                for (InetSocketAddress a : punchTargets) {
                    if (peer(a) == null) { begin(T_PUNCH); sendTo(a); }
                }
            }
            snapAcc += dt;
            float rate = g.state == Game.State.LOBBY ? 0.1f : 0.05f;
            if (snapAcc >= rate) {
                snapAcc = 0;
                snapId++;
                orbTick = !orbTick;
                for (Peer pe : peers) {
                    sendHead(pe);
                    if (g.state != Game.State.LOBBY) {
                        sendWorld(pe);
                        sendEvents(pe);
                    }
                }
                g.fxEvents.clear();
            }
            if (peers.isEmpty()) g.fxEvents.clear();
        } else {
            if (hostAddr == null) return;
            if (!connected) {
                helloAcc += dt;
                if (helloAcc >= 0.25f) {
                    helloAcc = 0;
                    begin(T_PUNCH);
                    sendTo(hostAddr);
                    ByteBuffer o = begin(T_HELLO);
                    o.put(VER);
                    o.put((byte) g.menuSel);
                    o.putInt(nonce);
                    sendTo(hostAddr);
                }
                return;
            }
            keepAcc += dt;
            if (g.state != Game.State.LOBBY || keepAcc >= 0.1f) {
                keepAcc = 0;
                sendInput();
            }
        }
    }

    private void sendInput() {
        ByteBuffer o = begin(T_INPUT);
        o.put((byte) myId);
        for (int i = 0; i < 4; i++) o.put((byte) castCount[i]);
        Player p = g.player;
        o.put((byte) (p != null && p.lmb ? 1 : 0));
        o.putFloat(p != null ? p.mouseX : 0);
        o.putFloat(p != null ? p.mouseZ : 0);
        o.putShort((short) myPickRound);
        o.put((byte) myPickChoice);
        float[][] h = history.toArray(new float[0][]);
        int n = Math.min(24, h.length); // onaylanmamış hareketlerin hepsi (kayıp/gecikmeye karşı)
        o.put((byte) n);
        for (int i = h.length - n; i < h.length; i++) {
            o.putInt((int) h[i][0]);
            o.put((byte) Math.round(h[i][1] * 100));
            o.put((byte) Math.round(h[i][2] * 100));
            o.put((byte) Math.min(255, Math.round(h[i][3] * 1000)));
        }
        sendTo(hostAddr);
    }

    private void sendHead(Peer pe) {
        ByteBuffer o = begin(T_HEAD);
        o.putInt(snapId);
        o.put((byte) g.gameId);
        o.put((byte) g.state.ordinal());
        o.putFloat(g.time);
        o.putInt(g.kills);
        o.putShort((short) g.teamLevel);
        o.putFloat(g.teamXp);
        o.putFloat(g.xpNext);
        o.putShort((short) g.pickRound);
        boolean bossAlive = g.boss != null && !g.boss.dead;
        o.put((byte) ((g.bossSpawned ? 1 : 0) | (bossAlive ? 2 : 0)));
        o.put(frac(bossAlive ? g.boss.hp / g.boss.maxHp : 0));
        o.put((byte) g.players.size());
        for (Player p : g.players) {
            o.put((byte) p.id);
            o.put((byte) p.champIndex);
            pos(o, p.x);
            pos(o, p.z);
            ang(o, p.face);
            o.putShort((short) Math.ceil(Math.max(0, p.hp)));
            o.putShort((short) Math.ceil(p.maxHp));
            o.putShort((short) Math.ceil(p.shield));
            o.put((byte) ((p.moving ? 1 : 0) | (p.downed ? 2 : 0) | (p.hurtFlash > 0 ? 4 : 0)));
            o.put(frac(p.reviveT / Game.REVIVE_TIME));
            o.putShort((short) Math.round(p.moveSpeed() * 100));
            Peer pp = peerById(p.id);
            o.putInt(pp != null ? pp.lastSeq : -1);
            o.put((byte) p.lvl("blades"));
            o.put((byte) p.lvl("aura"));
            p.champ.putVis(o);
        }
        Player me = g.find(pe.id);
        if (me != null) {
            o.put((byte) 1);
            for (int i = 0; i < 4; i++) o.putShort((short) Math.round(Math.max(0, me.champ.cd[i]) * 100));
            for (int i = 0; i < 4; i++) o.putShort((short) Math.round(me.champ.effCd(i) * 100));
            o.put((byte) (me.picked ? 1 : 0));
            o.put((byte) me.choices.size());
            for (Upgrade u : me.choices) str(o, u.id);
            int mark = o.position(), n = 0;
            o.put((byte) 0);
            for (Map.Entry<String, Integer> en : me.ups.entrySet()) {
                if (o.position() > 1450) break;
                str(o, en.getKey());
                o.put((byte) (int) en.getValue());
                n++;
            }
            o.put(mark, (byte) n);
        } else o.put((byte) 0);
        sendTo(pe.addr);
    }

    private void sendWorld(Peer pe) {
        Player me = g.find(pe.id);
        final float cx = me != null ? me.x : 0, cz = me != null ? me.z : 0;
        ArrayList<Enemy> el = new ArrayList<>();
        for (Enemy e : g.enemies) if (!e.dead && near(e.x, e.z, cx, cz)) el.add(e);
        sendCat(pe, C_ENEMY, el, 10, (o, e) -> {
            o.putShort((short) e.id);
            o.put((byte) (e.kind.ordinal() | (e.elite ? 0x80 : 0)));
            pos(o, e.x);
            pos(o, e.z);
            ang(o, e.face);
            o.put(frac(e.hp / e.maxHp));
            o.put((byte) ((e.flash > 0 ? 1 : 0) | (e.slowT > 0 ? 2 : 0) | (e.rootT > 0 ? 4 : 0)
                    | (e.markT > 0 ? 8 : 0) | (e.windT > 0 ? 16 : 0) | (e.chargeT > 0 ? 32 : 0)));
        });
        ArrayList<Projectile> pl = new ArrayList<>();
        for (Projectile p : g.projectiles) if (!p.dead && near(p.x, p.z, cx, cz)) pl.add(p);
        sendCat(pe, C_PROJ, pl, 13, (o, p) -> {
            o.putShort((short) p.id);
            o.put((byte) p.kind.ordinal());
            pos(o, p.x);
            o.put(Champion.q(p.y, 20));
            pos(o, p.z);
            o.putShort((short) Math.round(p.vx * 20));
            o.putShort((short) Math.round(p.vz * 20));
            o.put(Champion.q(p.age, 50));
        });
        if (orbTick) {
            ArrayList<Orb> ol = new ArrayList<>();
            for (Orb ob : g.orbs) if (ob.value >= 0 && near(ob.x, ob.z, cx, cz)) ol.add(ob);
            sendCat(pe, C_ORB, ol, 6, (o, ob) -> {
                pos(o, ob.x);
                pos(o, ob.z);
                o.put((byte) ob.type);
                o.put((byte) (ob.value <= 1 ? 1 : (ob.value <= 5 ? 5 : 10)));
            });
        }
        ArrayList<Trap> tl = new ArrayList<>();
        for (Trap t : g.traps) if (!t.dead && near(t.x, t.z, cx, cz)) tl.add(t);
        sendCat(pe, C_TRAP, tl, 6, (o, t) -> {
            pos(o, t.x);
            pos(o, t.z);
            o.put(Champion.q(t.age, 20));
            o.put(Champion.q(t.arm, 20));
        });
        ArrayList<Zone> zl = new ArrayList<>();
        for (Zone z : g.zones) if (!z.dead && near(z.x, z.z, cx, cz)) zl.add(z);
        sendCat(pe, C_ZONE, zl, 10, (o, z) -> {
            pos(o, z.x);
            pos(o, z.z);
            o.put(Champion.q(z.radius, 10));
            o.put(frac(z.life / z.max));
            o.put(Champion.q(z.max, 20));
            col(o, z.color);
        });
        ArrayList<Beam> bl = new ArrayList<>();
        for (Beam b : g.beams) if (!(b.fired && b.life <= 0)) bl.add(b);
        sendCat(pe, C_BEAM, bl, 16, (o, b) -> {
            pos(o, b.x1);
            pos(o, b.z1);
            pos(o, b.x2);
            pos(o, b.z2);
            o.put(Champion.q(b.width, 20));
            o.put((byte) ((b.fired ? 1 : 0) | (b.lightning ? 2 : 0)));
            o.put(frac(b.life / b.max));
            o.put(Champion.q(b.max, 50));
            o.put(Champion.q(b.delay, 100));
            col(o, b.color);
        });
        ArrayList<Telegraph> gl = new ArrayList<>();
        for (Telegraph t : g.telegraphs) if (!t.dead && near(t.x, t.z, cx, cz)) gl.add(t);
        sendCat(pe, C_TELE, gl, 10, (o, t) -> {
            pos(o, t.x);
            pos(o, t.z);
            o.put(Champion.q(t.radius, 10));
            o.put(frac(t.t / t.max));
            o.put(Champion.q(t.max, 20));
            col(o, t.color);
        });
        ArrayList<FallingSword> sl = new ArrayList<>();
        for (FallingSword s : g.swords) if (!s.dead && near(s.x, s.z, cx, cz)) sl.add(s);
        sendCat(pe, C_SWORD, sl, 7, (o, s) -> {
            pos(o, s.x);
            pos(o, s.z);
            o.put(frac(Math.max(0, s.t) / s.max));
            o.put(Champion.q(s.max, 50));
            o.put(Champion.q(s.stuck, 50));
        });
    }

    private static boolean near(float x, float z, float cx, float cz) {
        float dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz < CULL * CULL;
    }

    private <T> void sendCat(Peer pe, int cat, List<T> items, int size, BiConsumer<ByteBuffer, T> w) {
        int per = (MTU - 16) / size;
        int n = items.size();
        int chunks = Math.max(1, (n + per - 1) / per);
        if (chunks > 200) { chunks = 200; n = per * 200; }
        for (int c = 0; c < chunks; c++) {
            ByteBuffer o = begin(T_CHUNK);
            o.putInt(snapId);
            o.put((byte) cat);
            o.put((byte) c);
            o.put((byte) chunks);
            int from = c * per, to = Math.min(n, from + per);
            o.putShort((short) (to - from));
            for (int i = from; i < to; i++) w.accept(o, items.get(i));
            sendTo(pe.addr);
        }
    }

    private void sendEvents(Peer pe) {
        Player me = g.find(pe.id);
        if (me == null) return;
        ByteBuffer o = null;
        int cntPos = 0, cnt = 0, total = 0;
        for (FxEvent e : g.fxEvents) {
            if (e.positional()) {
                float dx = e.x - me.x, dz = e.z - me.z;
                if (dx * dx + dz * dz > CULL * CULL) continue;
            }
            if (e.type == FxEvent.SHAKE && e.n >= 0 && e.n != pe.id) continue;
            if (++total > 350) break;
            if (o == null || o.position() > MTU - 150) {
                if (o != null) { o.putShort(cntPos, (short) cnt); sendTo(pe.addr); }
                o = begin(T_EVENTS);
                cntPos = o.position();
                o.putShort((short) 0);
                cnt = 0;
            }
            writeEvent(o, e);
            cnt++;
        }
        if (o != null) { o.putShort(cntPos, (short) cnt); sendTo(pe.addr); }
    }

    private static void writeEvent(ByteBuffer o, FxEvent e) {
        o.put((byte) e.type);
        switch (e.type) {
            case FxEvent.BURST -> { pos(o, e.x); pos(o, e.y); pos(o, e.z); o.put((byte) Math.min(127, e.n)); col(o, e.color); o.put(Champion.q(e.a, 10)); }
            case FxEvent.SPARK -> { pos(o, e.x); pos(o, e.y); pos(o, e.z); col(o, e.color); }
            case FxEvent.RING -> { pos(o, e.x); pos(o, e.z); o.put(Champion.q(e.a, 10)); o.putShort((short) Math.round(e.b * 10)); o.put(Champion.q(e.c, 100)); col(o, e.color); }
            case FxEvent.TEXT -> { pos(o, e.x); pos(o, e.y); pos(o, e.z); col(o, e.color); o.put(Champion.q(e.a, 1)); str(o, e.s); }
            case FxEvent.BANNER -> { o.put(Champion.q(e.a, 10)); col(o, e.color); str(o, e.s); }
            case FxEvent.SHAKE -> o.put(Champion.q(e.a, 100));
            default -> { }
        }
    }

    // ================================================================== istemci: uygulama
    void castPressed(int slot) { castCount[slot] = (castCount[slot] + 1) & 0xff; }

    void clientPick(int i) {
        Player p = g.player;
        if (p == null || p.picked || i < 0 || i >= p.choices.size()) return;
        myPickRound = g.pickRound;
        myPickChoice = i;
        p.picked = true;
    }

    private void readHead(ByteBuffer b) {
        int sid = b.getInt();
        if (sid <= lastHeadSnap) return;
        lastHeadSnap = sid;
        int gid = b.get() & 0xff;
        Game.State st = Game.State.values()[b.get()];
        float time = b.getFloat();
        int kills = b.getInt();
        int lvl = b.getShort();
        float xp = b.getFloat(), xpNext = b.getFloat();
        int round = b.getShort();
        int bf = b.get();
        float bossFrac = frac(b);

        if (st == Game.State.LOBBY) {
            g.state = Game.State.LOBBY;
        } else {
            if (gid != curGameId || g.state == Game.State.LOBBY) {
                curGameId = gid;
                g.clientBeginRun();
                history.clear();
                enemyMap.clear();
                projMap.clear();
                Arrays.fill(applied, -1);
                Arrays.fill(asm, null);
                myPickRound = -1;
                myPickChoice = -1;
            }
            g.state = st;
        }
        g.time = time;
        g.kills = kills;
        g.teamLevel = lvl;
        g.teamXp = xp;
        g.xpNext = xpNext;
        g.pickRound = round;
        g.bossSpawned = (bf & 1) != 0;
        g.bossAliveNet = (bf & 2) != 0;
        g.bossFracNet = bossFrac;

        int np = b.get() & 0xff;
        HashSet<Integer> seen = new HashSet<>();
        for (int k = 0; k < np; k++) {
            int id = b.get(), champ = b.get();
            float x = pos(b), z = pos(b), face = ang(b);
            float hp = b.getShort(), maxHp = b.getShort(), shield = b.getShort();
            int fl = b.get();
            float rev = frac(b);
            float spd = b.getShort() / 100f;
            int lastSeq = b.getInt();
            int bl = b.get(), au = b.get();
            Player p = g.find(id);
            if (p == null || p.champIndex != champ) {
                if (p != null) g.players.remove(p);
                p = g.makePlayer(id, champ, id == myId);
                p.x = p.netX = x;
                p.z = p.netZ = z;
                g.players.add(p);
            }
            if (p.local) g.player = p;
            p.champ.getVis(b);
            seen.add(id);
            p.hp = hp;
            p.maxHp = maxHp;
            p.shield = shield;
            p.downed = (fl & 2) != 0;
            p.reviveT = rev * Game.REVIVE_TIME;
            if ((fl & 4) != 0) p.hurtFlash = 0.12f;
            p.face = face;
            if (!p.local) {
                p.netX = x;
                p.netZ = z;
                p.moving = (fl & 1) != 0;
                p.ups.put("blades", bl);
                p.ups.put("aura", au);
            } else {
                serverSpeed = spd;
                if (g.state == Game.State.PLAY || g.state == Game.State.LEVELUP) reconcile(p, x, z, lastSeq);
                else { p.x = x; p.z = z; }
            }
        }
        g.players.removeIf(q -> !seen.contains(q.id));
        g.players.sort(Comparator.comparingInt(q -> q.id));

        if (b.get() == 1 && g.player != null && g.player.id == myId) {
            Player me = g.player;
            for (int i = 0; i < 4; i++) me.champ.cd[i] = b.getShort() / 100f;
            for (int i = 0; i < 4; i++) me.champ.cdMax[i] = b.getShort() / 100f;
            me.cdMult = 1;
            boolean picked = b.get() != 0;
            int nc = b.get();
            List<Upgrade> ch = new ArrayList<>();
            for (int i = 0; i < nc; i++) {
                String id = str(b);
                Upgrade u = me.upIndex.get(id);
                if (u == null && id.equals("iksir")) u = Upgrade.fallback();
                if (u != null) ch.add(u);
            }
            me.choices = ch;
            if (myPickRound != round) myPickChoice = -1;
            boolean pending = myPickRound == round && myPickChoice >= 0;
            if (picked) myPickChoice = -1;
            me.picked = picked || pending;
            int nu = b.get();
            me.ups.clear();
            for (int i = 0; i < nu; i++) {
                String id = str(b);
                me.ups.put(id, b.get() & 0xff);
            }
        }
    }

    /** İstemci tarafı tahmin: sunucu konumundan onaylanmamış girdileri tekrar oynat. */
    private void reconcile(Player p, float sx, float sz, int lastSeq) {
        while (!history.isEmpty() && history.peekFirst()[0] <= lastSeq) history.pollFirst();
        if (p.downed) { p.x = sx; p.z = sz; history.clear(); return; }
        float x = sx, z = sz;
        float[] r = new float[2];
        for (float[] h : history) {
            Game.stepMove(x, z, h[1], h[2], serverSpeed, h[3], r);
            x = r[0];
            z = r[1];
        }
        float ex = x - p.x, ez = z - p.z;
        if (ex * ex + ez * ez > 4) { p.x = x; p.z = z; }
        else { p.x += ex * 0.5f; p.z += ez * 0.5f; }
    }

    private void readChunk(ByteBuffer b) {
        int sid = b.getInt();
        int cat = b.get();
        int idx = b.get() & 0xff, cnt = b.get() & 0xff;
        if (cat < 0 || cat >= CATS || cnt == 0 || sid <= applied[cat]) return;
        Asm a = asm[cat];
        if (a == null || a.snap < sid) {
            a = new Asm();
            a.snap = sid;
            a.parts = new byte[cnt][];
            asm[cat] = a;
        } else if (a.snap > sid) return;
        if (idx >= a.parts.length || a.parts[idx] != null) return;
        a.parts[idx] = Arrays.copyOfRange(b.array(), b.position(), b.limit());
        a.got++;
        if (a.got == a.parts.length) {
            applied[cat] = sid;
            asm[cat] = null;
            applyCat(cat, a.parts);
        }
    }

    private void applyCat(int cat, byte[][] parts) {
        switch (cat) {
            case C_ENEMY -> {
                HashSet<Integer> keep = new HashSet<>();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        int id = b.getShort() & 0xffff;
                        int kb = b.get() & 0xff;
                        EnemyKind kind = EnemyKind.values()[kb & 0x7f];
                        boolean elite = (kb & 0x80) != 0;
                        float x = pos(b), z = pos(b), face = ang(b), hpf = frac(b);
                        int fl = b.get();
                        Enemy e = enemyMap.get(id);
                        if (e == null) {
                            e = new Enemy();
                            e.id = id;
                            e.x = x;
                            e.z = z;
                            e.anim = rng.nextFloat() * 10;
                            enemyMap.put(id, e);
                        }
                        e.kind = kind;
                        e.elite = elite;
                        e.radius = kind.radius * (elite ? 1.4f : 1f);
                        e.nx = x;
                        e.nz = z;
                        e.face = face;
                        e.maxHp = 1;
                        e.hp = hpf;
                        if ((fl & 1) != 0) e.flash = 0.1f;
                        e.slowT = (fl & 2) != 0 ? 1 : 0;
                        e.rootT = (fl & 4) != 0 ? 1 : 0;
                        e.markT = (fl & 8) != 0 ? 1 : 0;
                        e.windT = (fl & 16) != 0 ? 1 : 0;
                        e.chargeT = (fl & 32) != 0 ? 1 : 0;
                        e.atkCd = 1;
                        keep.add(id);
                    }
                }
                enemyMap.keySet().retainAll(keep);
                g.enemies.clear();
                g.enemies.addAll(enemyMap.values());
            }
            case C_PROJ -> {
                HashSet<Integer> keep = new HashSet<>();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        int id = b.getShort() & 0xffff;
                        ProjKind kind = ProjKind.values()[b.get()];
                        float x = pos(b), y = (b.get() & 0xff) / 20f, z = pos(b);
                        float vx = b.getShort() / 20f, vz = b.getShort() / 20f, age = (b.get() & 0xff) / 50f;
                        Projectile p = projMap.get(id);
                        if (p == null) {
                            p = new Projectile();
                            p.id = id;
                            p.age = age;
                            projMap.put(id, p);
                        }
                        p.kind = kind;
                        p.x = x;
                        p.y = y;
                        p.z = z;
                        p.vx = vx;
                        p.vz = vz;
                        p.color = kindColor(kind);
                        keep.add(id);
                    }
                }
                projMap.keySet().retainAll(keep);
                g.projectiles.clear();
                g.projectiles.addAll(projMap.values());
            }
            case C_ORB -> {
                g.orbs.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        Orb o = new Orb();
                        o.x = pos(b);
                        o.z = pos(b);
                        o.type = b.get();
                        o.value = b.get();
                        o.age = g.menuAnim + ((o.x * 7 + o.z * 13) % 10 + 10) % 10;
                        g.orbs.add(o);
                    }
                }
            }
            case C_TRAP -> {
                g.traps.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        Trap t = new Trap();
                        t.x = pos(b);
                        t.z = pos(b);
                        t.age = (b.get() & 0xff) / 20f;
                        t.arm = (b.get() & 0xff) / 20f;
                        t.life = 1;
                        g.traps.add(t);
                    }
                }
            }
            case C_ZONE -> {
                g.zones.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        Zone zn = new Zone();
                        zn.x = pos(b);
                        zn.z = pos(b);
                        zn.radius = (b.get() & 0xff) / 10f;
                        float f = frac(b);
                        zn.max = Math.max(0.05f, (b.get() & 0xff) / 20f);
                        zn.life = f * zn.max;
                        zn.color = col(b);
                        g.zones.add(zn);
                    }
                }
            }
            case C_BEAM -> {
                g.beams.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        Beam bm = new Beam();
                        bm.x1 = pos(b);
                        bm.z1 = pos(b);
                        bm.x2 = pos(b);
                        bm.z2 = pos(b);
                        bm.width = (b.get() & 0xff) / 20f;
                        int fl = b.get();
                        bm.fired = (fl & 1) != 0;
                        bm.lightning = (fl & 2) != 0;
                        float f = frac(b);
                        bm.max = Math.max(0.02f, (b.get() & 0xff) / 50f);
                        bm.life = f * bm.max;
                        bm.delay = (b.get() & 0xff) / 100f;
                        bm.color = col(b);
                        g.beams.add(bm);
                    }
                }
            }
            case C_TELE -> {
                g.telegraphs.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        Telegraph t = new Telegraph();
                        t.x = pos(b);
                        t.z = pos(b);
                        t.radius = (b.get() & 0xff) / 10f;
                        float f = frac(b);
                        t.max = Math.max(0.05f, (b.get() & 0xff) / 20f);
                        t.t = f * t.max;
                        t.color = col(b);
                        g.telegraphs.add(t);
                    }
                }
            }
            case C_SWORD -> {
                g.swords.clear();
                for (byte[] part : parts) {
                    ByteBuffer b = ByteBuffer.wrap(part);
                    int n = b.getShort();
                    for (int i = 0; i < n; i++) {
                        FallingSword s = new FallingSword();
                        s.x = pos(b);
                        s.z = pos(b);
                        float f = frac(b);
                        s.max = Math.max(0.02f, (b.get() & 0xff) / 50f);
                        s.t = f * s.max;
                        s.stuck = (b.get() & 0xff) / 50f;
                        g.swords.add(s);
                    }
                }
            }
            default -> { }
        }
    }

    static int kindColor(ProjKind k) {
        return switch (k) {
            case BULLET -> 0xff4fa3;
            case ROCKET -> 0x2fb39a;
            case ZAP -> 0x7ff9ff;
            case MEGA -> 0xd8344a;
            case ORB -> 0xfff1a8;
            case BIND -> 0x9ff0ff;
            case SPIT -> 0x8cff5a;
        };
    }

    private void readEvents(ByteBuffer b) {
        int n = b.getShort();
        for (int i = 0; i < n; i++) {
            int t = b.get();
            switch (t) {
                case FxEvent.BURST -> {
                    float x = pos(b), y = pos(b), z = pos(b);
                    int cnt = b.get(), c = col(b);
                    float sp = (b.get() & 0xff) / 10f;
                    g.burst(x, y, z, cnt, c, sp);
                }
                case FxEvent.SPARK -> {
                    float x = pos(b), y = pos(b), z = pos(b);
                    g.spark(x, y, z, col(b));
                }
                case FxEvent.RING -> {
                    float x = pos(b), z = pos(b), r0 = (b.get() & 0xff) / 10f, r1 = b.getShort() / 10f, life = (b.get() & 0xff) / 100f;
                    g.ringFx(x, z, r0, r1, Math.max(0.05f, life), col(b));
                }
                case FxEvent.TEXT -> {
                    float x = pos(b), y = pos(b), z = pos(b);
                    int c = col(b);
                    float size = b.get() & 0xff;
                    g.popText(x, y, z, str(b), 0xff000000 | c, size);
                }
                case FxEvent.BANNER -> {
                    float life = (b.get() & 0xff) / 10f;
                    int c = col(b);
                    g.banner(str(b), life, 0xff000000 | c);
                }
                case FxEvent.SHAKE -> g.shakeAmt = Math.max(g.shakeAmt, (b.get() & 0xff) / 100f);
                default -> { return; }
            }
        }
    }

    /** İstemci: her karede tahmin, yumuşatma ve görsel zamanlayıcılar. */
    void clientVisual(float dt) {
        Player p = g.player;
        if (p != null) {
            if (connected && g.state == Game.State.PLAY && !p.downed) {
                seq++;
                history.addLast(new float[]{seq, p.inMx, p.inMz, dt});
                while (history.size() > 240) history.pollFirst();
                float[] r = Game.stepMove(p.x, p.z, p.inMx, p.inMz, serverSpeed, dt, new float[2]);
                p.x = r[0];
                p.z = r[1];
                p.moving = p.inMx != 0 || p.inMz != 0;
            } else p.moving = false;
        }
        float k = Math.min(1, dt * 12);
        for (Player q : g.players) {
            if (!q.local) {
                q.x += (q.netX - q.x) * k;
                q.z += (q.netZ - q.z) * k;
            }
            if (q.hurtFlash > 0) q.hurtFlash -= dt;
            q.champ.visualTick(dt);
        }
        for (Enemy e : g.enemies) {
            e.x += (e.nx - e.x) * k;
            e.z += (e.nz - e.z) * k;
            e.anim += dt;
            e.spawnT += dt;
            if (e.flash > 0) e.flash -= dt;
        }
        for (Projectile pr : g.projectiles) {
            pr.x += pr.vx * dt;
            pr.z += pr.vz * dt;
            pr.age += dt;
            g.projectileTrail(pr);
        }
        for (Orb o : g.orbs) o.age += dt;
        for (Trap t : g.traps) t.age += dt;
        for (Zone z : g.zones) z.life = Math.max(0, z.life - dt);
        for (Telegraph t : g.telegraphs) t.t = Math.max(0, t.t - dt);
        for (FallingSword s : g.swords) { if (s.t > 0) s.t -= dt; else s.stuck -= dt; }
        g.updateFxVisual(dt);
    }

    // ================================================================== kodlama yardımcıları
    private ByteBuffer begin(byte type) {
        out.clear();
        out.putShort(MAGIC);
        out.put(type);
        return out;
    }

    private void sendTo(InetSocketAddress a) { net.send(a, out.array(), out.position()); }

    static void pos(ByteBuffer b, float v) { b.putShort((short) Math.max(-32000, Math.min(32000, Math.round(v * 100)))); }

    static float pos(ByteBuffer b) { return b.getShort() / 100f; }

    static void ang(ByteBuffer b, float a) { b.put((byte) Math.round(a / (Math.PI * 2) * 256)); }

    static float ang(ByteBuffer b) { return b.get() * (float) (Math.PI * 2) / 256f; }

    static byte frac(float f) { return (byte) Math.round(Math.max(0, Math.min(1, f)) * 255); }

    static float frac(ByteBuffer b) { return (b.get() & 0xff) / 255f; }

    static void col(ByteBuffer b, int c) { b.put((byte) (c >> 16)); b.put((byte) (c >> 8)); b.put((byte) c); }

    static int col(ByteBuffer b) { return ((b.get() & 0xff) << 16) | ((b.get() & 0xff) << 8) | (b.get() & 0xff); }

    static void str(ByteBuffer b, String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(d.length, 120);
        b.put((byte) n);
        b.put(d, 0, n);
    }

    static String str(ByteBuffer b) {
        int n = b.get() & 0xff;
        byte[] d = new byte[n];
        b.get(d);
        return new String(d, StandardCharsets.UTF_8);
    }
}
