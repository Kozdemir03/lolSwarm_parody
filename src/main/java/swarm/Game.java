package swarm;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.net.SocketException;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class Game {
    static final float ARENA = 80f;
    static final float BOSS_TIME = 480f; // 8:00
    static final float REVIVE_TIME = 3f, REVIVE_RANGE = 2.6f;
    static final int FOG = 0x0d1220;
    static final int MAX_ENEMIES = 450;
    static final String[] KEYS = {"Q", "E", "R", "F"};
    static final int[] CAST_KEYS = {GLFW_KEY_Q, GLFW_KEY_E, GLFW_KEY_R, GLFW_KEY_F};

    enum State { MENU, LOBBY, PLAY, LEVELUP, PAUSE, DEAD, WIN }

    enum Role { SOLO, HOST, CLIENT }

    long window;
    int winW = 1600, winH = 900, fbW = 1600, fbH = 900;
    Renderer r;
    UI ui;
    final Input in = new Input();
    State state = State.MENU;
    Role role = Role.SOLO;
    NetSession net;
    final Random rng = new Random();

    /** Tüm oyuncular (host'ta simüle edilir, istemcide ağdan gelir). */
    final ArrayList<Player> players = new ArrayList<>();
    /** Bu bilgisayardaki oyuncu. */
    Player player = new Player();
    int menuSel;
    Champion[] previews;
    float menuAnim;
    boolean escMenu;
    String notice;
    float noticeT;

    final ArrayList<Enemy> enemies = new ArrayList<>();
    final ArrayList<Projectile> projectiles = new ArrayList<>();
    final ArrayList<Orb> orbs = new ArrayList<>();
    final ArrayList<Particle> particles = new ArrayList<>();
    final ArrayList<RingFx> rings = new ArrayList<>();
    final ArrayList<DmgText> texts = new ArrayList<>();
    final ArrayList<Trap> traps = new ArrayList<>();
    final ArrayList<Zone> zones = new ArrayList<>();
    final ArrayList<Beam> beams = new ArrayList<>();
    final ArrayList<Timed> timed = new ArrayList<>();
    final ArrayList<Telegraph> telegraphs = new ArrayList<>();
    final ArrayList<FallingSword> swords = new ArrayList<>();
    final ArrayList<Prop> props = new ArrayList<>();
    final ArrayList<FxEvent> fxEvents = new ArrayList<>();

    float time, spawnAcc, nextElite, nextWave;
    int kills, gameId, nextEnemyId = 1, nextProjId = 1;
    boolean bossSpawned;
    Enemy boss;
    boolean bossAliveNet;
    float bossFracNet;
    int teamLevel = 1, pendingLevels, pickRound;
    float teamXp, xpNext, levelUpDelay, endTimer;
    String banner;
    float bannerT;
    int bannerColor;

    final Matrix4f proj = new Matrix4f(), view = new Matrix4f(), pv = new Matrix4f(), invPv = new Matrix4f();
    final Vector3f camPos = new Vector3f();
    float camX, camZ, shakeAmt;
    float mouseX, mouseZ;
    final Matrix4f base = new Matrix4f();
    private final Vector4f v4 = new Vector4f(), v4b = new Vector4f();

    // uzaysal ızgara (çarpışma sorguları için)
    static final float CELL = 4f;
    static final int GN = (int) Math.ceil((ARENA * 2 + 8) / CELL);
    @SuppressWarnings("unchecked")
    final ArrayList<Enemy>[] grid = new ArrayList[GN * GN];
    final ArrayList<Enemy> tmpBuf = new ArrayList<>(), sepBuf = new ArrayList<>(), projBuf = new ArrayList<>(),
            aoeBuf = new ArrayList<>(), bladeBuf = new ArrayList<>();

    // ================================================================== yaşam döngüsü
    public void run() {
        init();
        long last = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = Math.min((now - last) / 1e9f, 0.05f);
            last = now;
            glfwPollEvents();
            querySize();
            update(dt);
            if (fbW > 0 && fbH > 0) render();
            in.endFrame();
            glfwSwapBuffers(window);
        }
        closeNet();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW başlatılamadı");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(winW, winH, "Rift Sürüsü — LoL Swarm Parodisi", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Pencere açılamadı");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        in.attach(window);

        glEnable(GL_MULTISAMPLE);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        r = new Renderer();
        ui = new UI();
        initLogic();
    }

    void initLogic() {
        previews = new Champion[]{create(0), create(1), create(2)};
        for (int i = 0; i < grid.length; i++) grid[i] = new ArrayList<>();
        genProps();
    }

    static Champion create(int i) {
        return switch (i) {
            case 0 -> new Kivilcim();
            case 1 -> new DemirMuhafiz();
            default -> new Lumina();
        };
    }

    private void querySize() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer w = s.mallocInt(1), h = s.mallocInt(1);
            glfwGetWindowSize(window, w, h);
            winW = Math.max(1, w.get(0));
            winH = Math.max(1, h.get(0));
            glfwGetFramebufferSize(window, w, h);
            fbW = w.get(0);
            fbH = h.get(0);
        }
    }

    private void genProps() {
        Random pr = new Random(1337);
        for (int i = 0; i < 170; i++) {
            Prop p = new Prop();
            do {
                p.x = (pr.nextFloat() * 2 - 1) * (ARENA - 4);
                p.z = (pr.nextFloat() * 2 - 1) * (ARENA - 4);
            } while (Math.hypot(p.x, p.z) < 10);
            p.type = pr.nextInt(4);
            p.rot = pr.nextFloat() * 6.28f;
            p.scale = 0.7f + pr.nextFloat() * 0.8f;
            props.add(p);
        }
    }

    // ================================================================== oyuncular / oturumlar
    Player makePlayer(int id, int champIdx, boolean local) {
        Player p = new Player();
        p.id = id;
        p.champIndex = champIdx;
        p.local = local;
        p.label = "P" + (id + 1);
        p.champ = create(champIdx).bind(p);
        p.maxHp = p.hp = p.champ.baseHp;
        p.speed = p.champ.baseSpeed;
        for (Upgrade u : Upgrade.generic()) p.upIndex.put(u.id, u);
        for (Upgrade u : p.champ.special()) p.upIndex.put(u.id, u);
        return p;
    }

    Player find(int id) {
        for (Player p : players) if (p.id == id) return p;
        return null;
    }

    void startSolo(int idx) {
        closeNet();
        role = Role.SOLO;
        players.clear();
        player = makePlayer(0, idx, true);
        players.add(player);
        beginRun();
    }

    void hostLobby() {
        closeNet();
        try {
            net = NetSession.host(this);
        } catch (SocketException e) {
            notice = "Ağ açılamadı: " + e.getMessage();
            noticeT = 5;
            return;
        }
        role = Role.HOST;
        players.clear();
        player = makePlayer(0, menuSel, true);
        players.add(player);
        enterLobby();
    }

    void joinLobby() {
        closeNet();
        try {
            net = NetSession.client(this);
        } catch (SocketException e) {
            notice = "Ağ açılamadı: " + e.getMessage();
            noticeT = 5;
            return;
        }
        role = Role.CLIENT;
        players.clear();
        player = makePlayer(-1, menuSel, true);
        enterLobby();
    }

    private void enterLobby() {
        state = State.LOBBY;
        in.typed.setLength(0);
        in.textMode = true;
    }

    void leaveToMenu(String msg) {
        closeNet();
        role = Role.SOLO;
        state = State.MENU;
        players.clear();
        escMenu = false;
        in.textMode = false;
        if (msg != null) { notice = msg; noticeT = 6; }
    }

    void closeNet() {
        if (net != null) {
            net.close();
            net = null;
        }
    }

    /** Host / tek oyuncu: yeni bir koşu başlat (oyuncular yenilenir, aynı şampiyonlar). */
    void beginRun() {
        List<Player> old = new ArrayList<>(players);
        players.clear();
        for (int i = 0; i < old.size(); i++) {
            Player o = old.get(i);
            Player p = makePlayer(o.id, o.champIndex, o.local);
            if (old.size() > 1) {
                double a = i * Math.PI * 2 / old.size();
                p.x = (float) Math.cos(a) * 2.5f;
                p.z = (float) Math.sin(a) * 2.5f;
            }
            players.add(p);
            if (p.local) player = p;
        }
        resetWorld();
        gameId = (gameId + 1) & 0x7f;
        state = State.PLAY;
        in.textMode = false;
        banner(players.size() > 1 ? "Birlikte hayatta kalın! 8:00'de Void Titanı uyanır." : "Hayatta kal! 8:00'de Void Titanı uyanır.", 4f, 0xffffffff);
    }

    /** İstemci: host yeni koşu başlattı. */
    void clientBeginRun() {
        resetWorld();
        in.textMode = false;
        escMenu = false;
    }

    private void resetWorld() {
        enemies.clear(); projectiles.clear(); orbs.clear(); particles.clear(); rings.clear(); texts.clear();
        traps.clear(); zones.clear(); beams.clear(); timed.clear(); telegraphs.clear(); swords.clear(); fxEvents.clear();
        time = 0; kills = 0; spawnAcc = 0; nextElite = 60; nextWave = 35; bossSpawned = false; boss = null;
        bossAliveNet = false;
        teamLevel = 1; teamXp = 0; xpNext = xpFor(1); pendingLevels = 0; pickRound = 0; endTimer = 0;
        camX = player.x;
        camZ = player.z;
        escMenu = false;
    }

    void removePlayer(int id) {
        Player p = find(id);
        if (p == null) return;
        players.remove(p);
        if (state == State.LEVELUP) finishPickIfDone();
        if (state == State.PLAY && allDown()) state = State.DEAD;
    }

    static float xpFor(int lvl) { return 6 + lvl * 5 + lvl * lvl * 0.35f; }

    boolean allDown() {
        for (Player p : players) if (!p.downed) return false;
        return true;
    }

    Player nearestAlive(float x, float z) {
        Player best = null;
        float bd = Float.MAX_VALUE;
        for (Player p : players) {
            if (p.downed) continue;
            float dx = p.x - x, dz = p.z - z, d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private Player randomAlive() {
        List<Player> a = new ArrayList<>();
        for (Player p : players) if (!p.downed) a.add(p);
        return a.isEmpty() ? player : a.get(rng.nextInt(a.size()));
    }

    // ================================================================== güncelleme
    void update(float dt) {
        menuAnim += dt;
        if (bannerT > 0) bannerT -= dt;
        if (noticeT > 0) noticeT -= dt;
        if (net != null) net.poll(dt);
        switch (state) {
            case MENU -> updateMenu();
            case LOBBY -> updateLobby();
            default -> updateGame(dt);
        }
        if (net != null) net.flush(dt);
    }

    private void updateMenu() {
        if (in.hit(GLFW_KEY_LEFT) || in.hit(GLFW_KEY_A)) menuSel = (menuSel + 2) % 3;
        if (in.hit(GLFW_KEY_RIGHT) || in.hit(GLFW_KEY_D)) menuSel = (menuSel + 1) % 3;
        if (in.hit(GLFW_KEY_1)) menuSel = 0;
        if (in.hit(GLFW_KEY_2)) menuSel = 1;
        if (in.hit(GLFW_KEY_3)) menuSel = 2;
        if (in.click(GLFW_MOUSE_BUTTON_LEFT)) {
            for (int i = 0; i < 3; i++) if (inside(tabRect(i))) menuSel = i;
            for (int i = 0; i < 3; i++) {
                if (!inside(modeRect(i))) continue;
                if (i == 0) startSolo(menuSel);
                else if (i == 1) hostLobby();
                else joinLobby();
                return;
            }
        }
        if (in.hit(GLFW_KEY_ENTER) || in.hit(GLFW_KEY_SPACE)) startSolo(menuSel);
        else if (in.hit(GLFW_KEY_H)) hostLobby();
        else if (in.hit(GLFW_KEY_J)) joinLobby();
        else if (in.hit(GLFW_KEY_ESCAPE)) glfwSetWindowShouldClose(window, true);
    }

    private void updateLobby() {
        in.textMode = true;
        if (in.hit(GLFW_KEY_ESCAPE)) { leaveToMenu(null); return; }
        if (in.mod() && in.hit(GLFW_KEY_V)) {
            String c = window != NULL ? glfwGetClipboardString(window) : null;
            if (c != null) {
                c = c.replaceAll("[\\r\\n\\t]", " ").trim();
                in.typed.append(c, 0, Math.min(c.length(), 48 - in.typed.length()));
            }
        }
        boolean copy = (in.mod() && in.hit(GLFW_KEY_C)) || (in.click(GLFW_MOUSE_BUTTON_LEFT) && inside(copyRect()));
        if (copy && net != null && net.myCode() != null && window != NULL) {
            glfwSetClipboardString(window, net.myCode());
            notice = "Kod kopyalandı: " + net.myCode();
            noticeT = 2.5f;
        }
        if (in.hit(GLFW_KEY_BACKSPACE) && in.typed.length() > 0) in.typed.setLength(in.typed.length() - 1);
        if (in.hit(GLFW_KEY_ENTER)) {
            String s = in.typed.toString().trim();
            if (!s.isEmpty()) {
                net.submitCode(s);
                in.typed.setLength(0);
            } else if (role == Role.HOST) {
                beginRun();
            }
        }
    }

    private void updateGame(float dt) {
        boolean mp = role != Role.SOLO;
        if (in.hit(GLFW_KEY_ESCAPE) || (!mp && in.hit(GLFW_KEY_P))) {
            if (!mp) {
                if (state == State.PLAY) { state = State.PAUSE; return; }
                if (state == State.PAUSE) { state = State.PLAY; return; }
            } else escMenu = !escMenu;
        }
        if (state == State.PAUSE) {
            if (in.hit(GLFW_KEY_M)) leaveToMenu(null);
            return;
        }
        if (mp && escMenu && in.hit(GLFW_KEY_M)) { leaveToMenu(null); return; }

        updateCamera(dt);
        updateMouseWorld();

        if (role == Role.CLIENT) {
            switch (state) {
                case PLAY -> readLocalInput();
                case LEVELUP -> localLevelUpInput(dt);
                case DEAD, WIN -> { if (in.hit(GLFW_KEY_M)) { leaveToMenu(null); return; } }
                default -> { }
            }
            if (net != null) net.clientVisual(dt);
            return;
        }

        switch (state) {
            case PLAY -> {
                readLocalInput();
                simulate(dt);
            }
            case LEVELUP -> localLevelUpInput(dt);
            case DEAD, WIN -> {
                updateFx(dt * 0.5f);
                cleanup();
                if (in.hit(GLFW_KEY_ENTER) || in.hit(GLFW_KEY_SPACE)) beginRun();
                if (in.hit(GLFW_KEY_M)) leaveToMenu(null);
            }
            default -> { }
        }
    }

    private void readLocalInput() {
        Player p = player;
        float mx = 0, mz = 0;
        if (!escMenu) {
            if (in.down(GLFW_KEY_W) || in.down(GLFW_KEY_UP)) mz -= 1;
            if (in.down(GLFW_KEY_S) || in.down(GLFW_KEY_DOWN)) mz += 1;
            if (in.down(GLFW_KEY_A) || in.down(GLFW_KEY_LEFT)) mx -= 1;
            if (in.down(GLFW_KEY_D) || in.down(GLFW_KEY_RIGHT)) mx += 1;
        }
        float len = (float) Math.sqrt(mx * mx + mz * mz);
        if (len > 0) { mx /= len; mz /= len; }
        p.inMx = mx;
        p.inMz = mz;
        p.mouseX = mouseX;
        p.mouseZ = mouseZ;
        p.lmb = !escMenu && in.mouseDown(GLFW_MOUSE_BUTTON_LEFT);
        if (escMenu || p.downed) return;
        for (int i = 0; i < 4; i++) {
            if (in.hit(CAST_KEYS[i])) {
                if (role == Role.CLIENT) net.castPressed(i);
                else p.castReq[i] = true;
            }
        }
    }

    private void simulate(float dt) {
        time += dt;
        for (Player p : players) updatePlayer(p, dt);
        for (Player p : players) {
            if (p.downed) {
                Arrays.fill(p.castReq, false);
                p.champ.anim += dt;
                continue;
            }
            p.champ.update(this, dt);
            for (int i = 0; i < 4; i++) {
                if (p.castReq[i]) {
                    p.castReq[i] = false;
                    p.champ.tryCast(this, i);
                }
            }
        }
        if (state != State.PLAY) return;
        spawnLogic(dt);
        rebuildGrid();
        updateEnemies(dt);
        rebuildGrid();
        updateWeapons(dt);
        updateProjectiles(dt);
        updateTraps(dt);
        updateZones(dt);
        updateTimed(dt);
        updateOrbs(dt);
        updateRevive(dt);
        updateFx(dt);
        cleanup();
        if (endTimer > 0) {
            endTimer -= dt;
            if (endTimer <= 0) state = State.WIN;
        }
        if (state == State.PLAY) checkLevelUp();
    }

    private void updateCamera(float dt) {
        float k = Math.min(1, dt * 8);
        camX += (player.x - camX) * k;
        camZ += (player.z - camZ) * k;
        float sx = 0, sy = 0;
        if (shakeAmt > 0) {
            shakeAmt = Math.max(0, shakeAmt - dt * 1.5f);
            sx = (rng.nextFloat() - .5f) * shakeAmt * 1.2f;
            sy = (rng.nextFloat() - .5f) * shakeAmt * 1.2f;
        }
        camPos.set(camX + sx, 24 + sy, camZ + 15);
        view.setLookAt(camPos.x, camPos.y, camPos.z, camX + sx, 0, camZ - 1, 0, 1, 0);
        proj.setPerspective((float) Math.toRadians(45), fbW / (float) Math.max(1, fbH), 0.5f, 220f);
        pv.set(proj).mul(view);
    }

    private void updateMouseWorld() {
        invPv.set(pv).invert();
        float nx = (float) (in.mx / winW * 2 - 1), ny = (float) (1 - in.my / winH * 2);
        v4.set(nx, ny, -1, 1).mul(invPv);
        v4.div(v4.w);
        v4b.set(nx, ny, 1, 1).mul(invPv);
        v4b.div(v4b.w);
        float t = v4.y / (v4.y - v4b.y);
        mouseX = v4.x + (v4b.x - v4.x) * t;
        mouseZ = v4.z + (v4b.z - v4.z) * t;
    }

    private void updatePlayer(Player p, float dt) {
        if (p.faceLock > 0) p.faceLock -= dt;
        if (p.shieldT > 0) { p.shieldT -= dt; if (p.shieldT <= 0) p.shield = 0; }
        if (p.drT > 0) p.drT -= dt;
        if (p.hurtFlash > 0) p.hurtFlash -= dt;
        p.moving = false;
        if (p.downed) { p.moveQueue.clear(); return; }
        if (p.local) move(p, p.inMx, p.inMz, dt);
        else {
            float[] m;
            while ((m = p.moveQueue.poll()) != null) move(p, m[0], m[1], m[2]);
        }
        p.heal(p.regen * dt);
    }

    private final float[] mv = new float[2];

    private void move(Player p, float mx, float mz, float dt) {
        if (mx * mx + mz * mz < 1e-6f) return;
        stepMove(p.x, p.z, mx, mz, p.moveSpeed(), dt, mv);
        p.x = mv[0];
        p.z = mv[1];
        p.moving = true;
        if (p.faceLock <= 0) p.face = turn(p.face, (float) Math.atan2(mx, mz), dt * 14);
    }

    /** Saf hareket adımı (istemci tahmini de bunu kullanır). */
    static float[] stepMove(float x, float z, float mx, float mz, float speed, float dt, float[] out) {
        float len = (float) Math.sqrt(mx * mx + mz * mz);
        if (len < 1e-4f) { out[0] = x; out[1] = z; return out; }
        out[0] = clampA(x + mx / len * speed * dt);
        out[1] = clampA(z + mz / len * speed * dt);
        return out;
    }

    static float turn(float from, float to, float maxStep) {
        float d = to - from;
        while (d > Math.PI) d -= (float) (Math.PI * 2);
        while (d < -Math.PI) d += (float) (Math.PI * 2);
        if (Math.abs(d) <= maxStep) return to;
        return from + Math.signum(d) * maxStep;
    }

    static float clampA(float v) { return Math.max(-ARENA + 1, Math.min(ARENA - 1, v)); }

    // ------------------------------------------------------------------ doğurma
    private void spawnLogic(float dt) {
        int n = Math.max(1, players.size());
        float rate = Math.min(13f + 4 * (n - 1), (0.9f + time / 28f) * (1 + 0.6f * (n - 1)));
        if (boss != null && !boss.dead) rate *= 0.45f;
        spawnAcc += dt * rate;
        while (spawnAcc >= 1) {
            spawnAcc -= 1;
            if (enemies.size() < MAX_ENEMIES) spawnAround(pickKind(), false);
        }
        if (time >= nextElite) {
            nextElite += 60;
            for (int i = 0; i < (n + 1) / 2; i++)
                spawnAround(time > 200 && rng.nextBoolean() ? EnemyKind.SPITTER : EnemyKind.BRUTE, true);
            banner("Elit düşman yaklaşıyor!", 2.5f, 0xffffd84a);
        }
        if (time >= nextWave) {
            nextWave += 50;
            Player a = randomAlive();
            int cnt = (int) Math.min(70, 20 + time / 10);
            EnemyKind k = time > 120 && rng.nextBoolean() ? EnemyKind.RUNNER : EnemyKind.MINION;
            for (int i = 0; i < cnt; i++) {
                double ang = i * Math.PI * 2 / cnt;
                spawnEnemy(k, false, a.x + (float) Math.cos(ang) * 21, a.z + (float) Math.sin(ang) * 21);
            }
            banner("SÜRÜ DALGASI!", 2f, 0xffff7a5a);
        }
        if (!bossSpawned && time >= BOSS_TIME) {
            bossSpawned = true;
            boss = spawnAround(EnemyKind.BOSS, false);
            banner("VOID TİTANI UYANDI!", 4f, 0xffff3a6a);
            shake(0.7f);
        }
    }

    private EnemyKind pickKind() {
        float roll = rng.nextFloat();
        if (time > 120 && roll < 0.10f) return EnemyKind.SPITTER;
        if (time > 80 && roll < 0.22f) return EnemyKind.BRUTE;
        if (time > 35 && roll < 0.45f) return EnemyKind.RUNNER;
        return EnemyKind.MINION;
    }

    Enemy spawnAround(EnemyKind k, boolean elite) {
        Player a = randomAlive();
        double ang = rng.nextDouble() * Math.PI * 2;
        float d = 31 + rng.nextFloat() * 5;
        float x = a.x + (float) Math.cos(ang) * d, z = a.z + (float) Math.sin(ang) * d;
        if (Math.abs(x) > ARENA - 2) x = a.x - (float) Math.cos(ang) * d;
        if (Math.abs(z) > ARENA - 2) z = a.z - (float) Math.sin(ang) * d;
        return spawnEnemy(k, elite, x, z);
    }

    Enemy spawnEnemy(EnemyKind k, boolean elite, float x, float z) {
        int n = Math.max(1, players.size());
        Enemy e = new Enemy();
        e.id = nextEnemyId++ & 0xffff;
        e.kind = k;
        e.x = clampA(x);
        e.z = clampA(z);
        float hpS = (1 + time / 60f * 0.55f) * (1 + 0.35f * (n - 1));
        e.maxHp = e.hp = k == EnemyKind.BOSS ? k.hp * (1 + teamLevel * 0.03f) * (1 + 0.6f * (n - 1)) : k.hp * hpS * (elite ? 10 : 1);
        e.speed = k.speed * (0.9f + rng.nextFloat() * 0.2f) * (1 + Math.min(0.25f, time / 900f)) * (elite ? 0.9f : 1f);
        e.dmg = k.dmg * (1 + time / 240f) * (elite ? 1.6f : 1f);
        e.radius = k.radius * (elite ? 1.4f : 1f);
        e.xp = elite ? 25 : k.xp;
        e.elite = elite;
        e.atkCd = 1;
        e.anim = rng.nextFloat() * 10;
        enemies.add(e);
        return e;
    }

    // ------------------------------------------------------------------ ızgara
    private int cellOf(float v) {
        int c = (int) ((v + ARENA + 4) / CELL);
        return c < 0 ? 0 : Math.min(GN - 1, c);
    }

    private void rebuildGrid() {
        for (ArrayList<Enemy> l : grid) l.clear();
        for (Enemy e : enemies) if (!e.dead) grid[cellOf(e.x) + cellOf(e.z) * GN].add(e);
    }

    ArrayList<Enemy> near(float x, float z, float rad, ArrayList<Enemy> out) {
        out.clear();
        int x0 = cellOf(x - rad), x1 = cellOf(x + rad), z0 = cellOf(z - rad), z1 = cellOf(z + rad);
        for (int cz = z0; cz <= z1; cz++)
            for (int cx = x0; cx <= x1; cx++) out.addAll(grid[cx + cz * GN]);
        return out;
    }

    Enemy nearest(float x, float z, float range) { return nearestExcluding(x, z, range, null); }

    Enemy nearestExcluding(float x, float z, float range, Set<Enemy> skip) {
        Enemy best = null;
        float bd = range * range;
        for (Enemy e : enemies) {
            if (e.dead || (skip != null && skip.contains(e))) continue;
            float dx = e.x - x, dz = e.z - z, d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    // ------------------------------------------------------------------ düşmanlar
    private void updateEnemies(float dt) {
        for (int i = 0; i < enemies.size(); i++) {
            final Enemy e = enemies.get(i);
            if (e.dead) continue;
            e.anim += dt;
            e.spawnT += dt;
            if (e.flash > 0) e.flash -= dt;
            if (e.slowT > 0) e.slowT -= dt; else e.slow = 0;
            if (e.rootT > 0) e.rootT -= dt;
            if (e.markT > 0) e.markT -= dt;
            if (e.bladeCd > 0) e.bladeCd -= dt;
            if (e.windT > 0) e.windT -= dt;
            e.atkCd -= dt;

            Player p = nearestAlive(e.x, e.z);
            if (p == null) continue;
            float dx = p.x - e.x, dz = p.z - e.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz) + 1e-4f;
            float nx = dx / dist, nz = dz / dist;
            e.face = turn(e.face, (float) Math.atan2(nx, nz), dt * 8);
            float spd = e.speed * (1 - e.slow);
            if (e.rootT > 0) spd = 0;
            float mvx = nx * spd, mvz = nz * spd;

            if (e.kind == EnemyKind.SPITTER) {
                if (dist < 8) { mvx = -nx * spd * 0.6f; mvz = -nz * spd * 0.6f; }
                else if (dist < 12) { mvx = -nz * spd * 0.5f; mvz = nx * spd * 0.5f; }
                if (e.atkCd <= 0 && dist < 15 && e.rootT <= 0) {
                    e.atkCd = 2.4f;
                    enemyShoot(e.x, e.z, nx, nz, 9f, e.dmg);
                }
            } else if (e.kind == EnemyKind.BOSS) {
                if (e.windT > 0) { mvx = 0; mvz = 0; }
                if (e.chargeT > 0) {
                    e.chargeT -= dt;
                    mvx = e.cdx * 18;
                    mvz = e.cdz * 18;
                    if (rng.nextFloat() < 0.6f) trail(e.x, 0.5f, e.z, 0xb04aff);
                }
                bossAI(e, dt);
            }

            float sx = 0, sz = 0;
            near(e.x, e.z, e.radius + 2.8f, sepBuf);
            for (Enemy o : sepBuf) {
                if (o == e || o.dead) continue;
                float ox = e.x - o.x, oz = e.z - o.z;
                float min = e.radius + o.radius, d2 = ox * ox + oz * oz;
                if (d2 < min * min) {
                    if (d2 < 1e-6f) { ox = rng.nextFloat() - .5f; oz = rng.nextFloat() - .5f; d2 = ox * ox + oz * oz + 1e-6f; }
                    float d = (float) Math.sqrt(d2);
                    float push = (min - d) / min;
                    float w = o.radius / (e.radius + o.radius) * 2;
                    sx += ox / d * push * w;
                    sz += oz / d * push * w;
                }
            }
            e.x = clampA(e.x + (mvx + sx * 7f + e.kx) * dt);
            e.z = clampA(e.z + (mvz + sz * 7f + e.kz) * dt);
            float kd = Math.min(1, dt * 7);
            e.kx -= e.kx * kd;
            e.kz -= e.kz * kd;

            // temas hasarı (en yakın canlı oyuncuya)
            float pr = e.radius + 0.45f;
            float ddx = e.x - p.x, ddz = e.z - p.z, d2 = ddx * ddx + ddz * ddz;
            if (d2 < pr * pr) {
                float d = (float) Math.sqrt(d2) + 1e-4f;
                e.x = p.x + ddx / d * pr;
                e.z = p.z + ddz / d * pr;
                if (e.atkCd <= 0 && e.kind != EnemyKind.SPITTER) {
                    e.atkCd = 0.9f;
                    hurtPlayer(p, e.chargeT > 0 ? e.dmg * 1.5f : e.dmg);
                }
            }
        }
    }

    private void bossAI(Enemy e, float dt) {
        e.bossT -= dt;
        if (e.bossT > 0) return;
        e.bossPhase = (e.bossPhase + 1) % 3;
        e.bossT = 3.4f;
        float dmgS = 1 + time / 240f;
        switch (e.bossPhase) {
            case 0 -> { // her canlı oyuncuya yere çarpma
                int i = 0;
                for (Player tp : new ArrayList<>(players)) {
                    if (tp.downed) continue;
                    for (int k = 0; k < (players.size() > 1 ? 2 : 3); k++, i++) {
                        final float tx0 = tp.x + (k == 0 ? 0 : (rng.nextFloat() - .5f) * 10);
                        final float tz0 = tp.z + (k == 0 ? 0 : (rng.nextFloat() - .5f) * 10);
                        final float rad = 5f;
                        telegraph(tx0, tz0, rad, 1.1f + k * 0.25f, 0xff3a6a, () -> {
                            for (Player q : players)
                                if (!q.downed && Math.hypot(q.x - tx0, q.z - tz0) < rad) hurtPlayer(q, 35 * dmgS);
                            explosion(tx0, tz0, rad, 0xb04aff);
                            shake(0.4f);
                        });
                    }
                }
            }
            case 1 -> { // çağırma + radyal tükürük
                for (int i = 0; i < 10; i++) {
                    double a = i * Math.PI * 2 / 10;
                    spawnEnemy(EnemyKind.MINION, false, e.x + (float) Math.cos(a) * 4.5f, e.z + (float) Math.sin(a) * 4.5f);
                }
                for (int i = 0; i < 18; i++) {
                    double a = i * Math.PI * 2 / 18;
                    enemyShoot(e.x, e.z, (float) Math.sin(a), (float) Math.cos(a), 8f, 14 * dmgS);
                }
                ringFx(e.x, e.z, 1, 7, 0.5f, 0xb04aff);
            }
            case 2 -> { // hücum
                e.windT = 0.8f;
                e.flash = 0.8f;
                later(0.8f, () -> {
                    Player t = nearestAlive(e.x, e.z);
                    if (e.dead || t == null) return;
                    float dx = t.x - e.x, dz = t.z - e.z;
                    float l = (float) Math.max(0.01, Math.hypot(dx, dz));
                    e.cdx = dx / l;
                    e.cdz = dz / l;
                    e.chargeT = 0.9f;
                    shake(0.3f);
                });
            }
        }
    }

    void enemyShoot(float x, float z, float dx, float dz, float speed, float dmg) {
        Projectile p = shoot(null, ProjKind.SPIT, x, z, dx, dz, speed, dmg, 22, 0.35f, 0x8cff5a);
        p.enemy = true;
        p.y = 0.9f;
    }

    void hurtPlayer(Player p, float amt) {
        if (state != State.PLAY || p.downed) return;
        amt *= 1 - Math.min(0.6f, p.armor);
        if (p.drT > 0) amt *= 1 - p.dr;
        if (p.shield > 0) {
            float a = Math.min(p.shield, amt);
            p.shield -= a;
            amt -= a;
        }
        if (amt <= 0) return;
        p.hp -= amt;
        p.hurtFlash = 0.15f;
        p.lastHit = time;
        shakeFor(p, 0.18f);
        if (p.hp <= 0) {
            p.hp = 0;
            p.downed = true;
            p.reviveT = 0;
            p.shield = 0;
            burst(p.x, 1, p.z, 30, p.champ.color, 5);
            if (allDown()) {
                state = State.DEAD;
            } else {
                banner(p.displayName() + " düştü! Yanına gidip kaldırın.", 3f, 0xffff4a5a);
            }
        }
    }

    private void updateRevive(float dt) {
        for (Player p : players) {
            if (!p.downed) continue;
            boolean helper = false;
            for (Player q : players) {
                if (q == p || q.downed) continue;
                float dx = q.x - p.x, dz = q.z - p.z;
                if (dx * dx + dz * dz < REVIVE_RANGE * REVIVE_RANGE) { helper = true; break; }
            }
            if (helper) {
                p.reviveT += dt;
                if (rng.nextFloat() < dt * 20) spark(p.x + rng.nextFloat() - .5f, 0.5f, p.z + rng.nextFloat() - .5f, 0x5ad17a);
                if (p.reviveT >= REVIVE_TIME) {
                    p.downed = false;
                    p.hp = p.maxHp * 0.4f;
                    p.reviveT = 0;
                    ringFx(p.x, p.z, 0.5f, 3f, 0.5f, 0x5ad17a);
                    burst(p.x, 1, p.z, 16, 0x5ad17a, 3);
                    banner(p.displayName() + " ayağa kalktı!", 2f, 0xff5ad17a);
                }
            } else p.reviveT = Math.max(0, p.reviveT - dt * 0.5f);
        }
    }

    // ------------------------------------------------------------------ hasar
    void damage(Player owner, Enemy e, float amt, boolean canCrit) { damage(owner, e, amt, canCrit, true); }

    void damage(Player owner, Enemy e, float amt, boolean canCrit, boolean showText) {
        if (e.dead) return;
        float critCh = owner != null ? owner.crit : 0, mult = owner != null ? owner.dmgMult : 1;
        boolean crit = canCrit && rng.nextFloat() < critCh;
        float d = amt * mult * (crit ? 2f : 1f);
        e.hp -= d;
        e.flash = 0.1f;
        if (showText) popText(e.x, 1.6f + e.radius, e.z, String.valueOf(Math.round(d)), crit ? 0xffffd84a : 0xffffffff, crit ? 26 : 18);
        if (e.hp <= 0) kill(owner, e);
    }

    void execute(Player owner, Enemy e) {
        if (e.dead) return;
        popText(e.x, 2.2f, e.z, "İNFAZ!", 0xffe0b23a, 26);
        e.hp = 0;
        kill(owner, e);
    }

    void kill(Player owner, Enemy e) {
        if (e.dead) return;
        e.dead = true;
        kills++;
        burst(e.x, 0.6f, e.z, 6 + (int) (e.radius * 6), e.kind.color, 4 + e.radius * 2);
        if (e.kind == EnemyKind.BOSS) {
            explosion(e.x, e.z, 10, 0xff4fa3);
            explosion(e.x, e.z, 6, 0xffd84a);
            shake(1f);
            banner("VOID TİTANI YENİLDİ!", 4f, 0xffffd84a);
            endTimer = 2.5f;
        } else {
            dropOrb(e.x, e.z, e.xp, Orb.XP);
            if (e.elite) {
                for (int i = 0; i < 5; i++) dropOrb(e.x + rng.nextFloat() * 2 - 1, e.z + rng.nextFloat() * 2 - 1, 6, Orb.XP);
                dropOrb(e.x + 1, e.z, 0, Orb.HEAL);
                if (rng.nextBoolean()) dropOrb(e.x - 1, e.z, 0, Orb.MAGNET);
            } else if (rng.nextFloat() < 0.005f) dropOrb(e.x, e.z, 0, Orb.HEAL);
            else if (rng.nextFloat() < 0.0015f) dropOrb(e.x, e.z, 0, Orb.MAGNET);
        }
        if (owner != null && owner.champ != null) owner.champ.onKill(this, e);
    }

    int aoe(Player owner, float x, float z, float rad, float dmg, Consumer<Enemy> after) { return aoe(owner, x, z, rad, dmg, after, true); }

    int aoe(Player owner, float x, float z, float rad, float dmg, Consumer<Enemy> after, boolean showText) {
        near(x, z, rad + 3, aoeBuf);
        int n = 0;
        for (Enemy e : aoeBuf) {
            if (e.dead) continue;
            float dx = e.x - x, dz = e.z - z, rr = rad + e.radius * 0.6f;
            if (dx * dx + dz * dz <= rr * rr) {
                damage(owner, e, dmg, true, showText);
                if (after != null) after.accept(e);
                n++;
            }
        }
        return n;
    }

    void knock(Enemy e, float fx, float fz, float force) {
        if (e.kind == EnemyKind.BOSS || e.dead) return;
        float dx = e.x - fx, dz = e.z - fz, l = (float) Math.max(0.01, Math.hypot(dx, dz));
        float f = force / Math.max(0.5f, e.radius * (e.elite ? 2 : 1));
        e.kx += dx / l * f;
        e.kz += dz / l * f;
    }

    // ------------------------------------------------------------------ şampiyon API'si
    /** Temel saldırı hedefi: sol tık basılıysa fare, değilse en yakın düşman. → p.aimX/aimZ */
    boolean aim(Player p, float range) {
        if (p.lmb) {
            p.aimX = p.mouseX;
            p.aimZ = p.mouseZ;
        } else {
            Enemy e = nearest(p.x, p.z, range);
            if (e == null) return false;
            p.aimX = e.x;
            p.aimZ = e.z;
        }
        p.face = (float) Math.atan2(p.aimX - p.x, p.aimZ - p.z);
        p.faceLock = 0.35f;
        return true;
    }

    /** Fare noktasını oyuncudan en fazla maxDist uzaklığa kısıtlar → p.tx, p.tz. */
    void target(Player p, float maxDist) {
        float dx = p.mouseX - p.x, dz = p.mouseZ - p.z;
        float d = (float) Math.hypot(dx, dz);
        if (d > maxDist) { dx *= maxDist / d; dz *= maxDist / d; }
        p.tx = clampA(p.x + dx);
        p.tz = clampA(p.z + dz);
    }

    Projectile shoot(Player owner, ProjKind k, float x, float z, float dx, float dz, float speed, float dmg, float range, float radius, int color) {
        float len = (float) Math.hypot(dx, dz);
        if (len < 1e-4f) { dx = 0; dz = 1; len = 1; }
        dx /= len;
        dz /= len;
        Projectile p = new Projectile();
        p.id = nextProjId++ & 0xffff;
        p.owner = owner;
        p.kind = k;
        p.x = x + dx * 0.6f;
        p.z = z + dz * 0.6f;
        p.vx = dx * speed;
        p.vz = dz * speed;
        p.dmg = dmg;
        p.range = range;
        p.radius = radius;
        p.color = color;
        projectiles.add(p);
        return p;
    }

    void later(float t, Runnable action) { timed.add(new Timed(t, action)); }

    void telegraph(float x, float z, float radius, float t, int color, Runnable fire) {
        Telegraph tg = new Telegraph();
        tg.x = x; tg.z = z; tg.radius = radius; tg.t = tg.max = t; tg.fire = fire; tg.color = color;
        telegraphs.add(tg);
    }

    // ------------------------------------------------------------------ efektler (host'ta ağa da yazılır)
    private boolean rec() { return role == Role.HOST && net != null && net.hasPeers(); }

    void shake(float a) {
        shakeAmt = Math.max(shakeAmt, a);
        if (rec()) { FxEvent e = new FxEvent(FxEvent.SHAKE); e.a = a; e.n = -1; fxEvents.add(e); }
    }

    void shakeFor(Player p, float a) {
        if (p.local) shakeAmt = Math.max(shakeAmt, a);
        else if (rec()) { FxEvent e = new FxEvent(FxEvent.SHAKE); e.a = a; e.n = p.id; fxEvents.add(e); }
    }

    void banner(String s, float t, int argb) {
        banner = s;
        bannerT = t;
        bannerColor = argb;
        if (rec()) { FxEvent e = new FxEvent(FxEvent.BANNER); e.s = s; e.a = t; e.color = argb; fxEvents.add(e); }
    }

    void burst(float x, float y, float z, int n, int color, float speed) {
        if (rec()) { FxEvent e = new FxEvent(FxEvent.BURST); e.x = x; e.y = y; e.z = z; e.n = n; e.color = color; e.a = speed; fxEvents.add(e); }
        for (int i = 0; i < n && particles.size() < 3000; i++) {
            Particle p = new Particle();
            p.x = x; p.y = y; p.z = z;
            double a = rng.nextDouble() * Math.PI * 2;
            float s = speed * (0.3f + rng.nextFloat());
            p.vx = (float) Math.cos(a) * s;
            p.vz = (float) Math.sin(a) * s;
            p.vy = 1 + rng.nextFloat() * speed * 0.8f;
            p.life = p.max = 0.35f + rng.nextFloat() * 0.45f;
            p.size = 0.1f + rng.nextFloat() * 0.15f;
            p.color = color;
            p.grav = 12;
            particles.add(p);
        }
    }

    void spark(float x, float y, float z, int color) {
        if (rec()) { FxEvent e = new FxEvent(FxEvent.SPARK); e.x = x; e.y = y; e.z = z; e.color = color; fxEvents.add(e); }
        trail(x, y, z, color);
    }

    /** Ağa gönderilmeyen küçük kıvılcım (mermi izleri: istemci kendisi üretir). */
    void trail(float x, float y, float z, int color) {
        if (particles.size() >= 3000) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.z = z;
        p.vx = (rng.nextFloat() - .5f) * 1.5f;
        p.vz = (rng.nextFloat() - .5f) * 1.5f;
        p.vy = 1 + rng.nextFloat() * 1.5f;
        p.life = p.max = 0.3f + rng.nextFloat() * 0.3f;
        p.size = 0.08f + rng.nextFloat() * 0.1f;
        p.color = color;
        p.grav = 2;
        particles.add(p);
    }

    void ringFx(float x, float z, float r0, float r1, float life, int color) {
        if (rec()) { FxEvent e = new FxEvent(FxEvent.RING); e.x = x; e.z = z; e.a = r0; e.b = r1; e.c = life; e.color = color; fxEvents.add(e); }
        RingFx f = new RingFx();
        f.x = x; f.z = z; f.r0 = r0; f.r1 = r1; f.life = f.max = life; f.color = color;
        rings.add(f);
    }

    void explosion(float x, float z, float radius, int color) {
        burst(x, 0.6f, z, (int) (8 + radius * 5), color, radius * 2.5f);
        burst(x, 0.6f, z, (int) (4 + radius * 2), 0xffd27a, radius * 2f);
        ringFx(x, z, 0.3f, radius, 0.35f, color);
    }

    void popText(float x, float y, float z, String s, int argb, float size) {
        if (rec()) { FxEvent e = new FxEvent(FxEvent.TEXT); e.x = x; e.y = y; e.z = z; e.s = s; e.color = argb; e.a = size; fxEvents.add(e); }
        if (texts.size() > 140) return;
        DmgText t = new DmgText();
        t.x = x + (rng.nextFloat() - .5f) * 0.6f;
        t.y = y;
        t.z = z;
        t.s = s;
        t.color = argb;
        t.size = size;
        t.life = 0.8f;
        texts.add(t);
    }

    void projectileTrail(Projectile p) {
        if (p.kind == ProjKind.MEGA) {
            for (int i = 0; i < 2; i++) trail(p.x - p.vx * 0.03f, p.y, p.z - p.vz * 0.03f, rng.nextBoolean() ? 0xff7a2a : 0xffd84a);
        } else if (p.kind == ProjKind.ROCKET && rng.nextFloat() < 0.6f) trail(p.x, p.y, p.z, 0x9aa3ad);
        else if (p.kind == ProjKind.ZAP && rng.nextFloat() < 0.8f) trail(p.x, p.y, p.z, 0x7ff9ff);
    }

    // ------------------------------------------------------------------ genel silahlar (oyuncu başına)
    float auraRadius(Player p) { return (2.6f + 0.4f * p.lvl("aura")) * p.area; }

    private void updateWeapons(float dt) {
        for (Player p : players) {
            if (p.downed) continue;
            int bl = p.lvl("blades");
            if (bl > 0) {
                int n = 1 + bl;
                float rad = 3.0f * p.area;
                p.bladeAngle += dt * 3.2f;
                float dmg = 10 + 5 * bl;
                for (int k = 0; k < n; k++) {
                    double a = p.bladeAngle + k * Math.PI * 2 / n;
                    float bx = p.x + (float) Math.cos(a) * rad, bz = p.z + (float) Math.sin(a) * rad;
                    near(bx, bz, 4, bladeBuf);
                    for (Enemy e : bladeBuf) {
                        if (e.dead || e.bladeCd > 0) continue;
                        float dx = e.x - bx, dz = e.z - bz, rr = 0.7f + e.radius;
                        if (dx * dx + dz * dz < rr * rr) {
                            damage(p, e, dmg, true);
                            e.bladeCd = 0.45f;
                            knock(e, p.x, p.z, 4);
                        }
                    }
                }
            }
            int cl = p.lvl("chain");
            if (cl > 0) {
                p.chainTimer -= dt * p.asMult;
                if (p.chainTimer <= 0) {
                    p.chainTimer = Math.max(0.9f, 2.6f - 0.3f * cl);
                    Enemy cur = nearest(p.x, p.z, 14);
                    if (cur != null) {
                        HashSet<Enemy> hit = new HashSet<>();
                        float px = p.x, pz = p.z, dmg = 22 + 9 * cl;
                        int jumps = 2 + cl;
                        for (int j = 0; j <= jumps && cur != null; j++) {
                            lightning(px, pz, cur.x, cur.z);
                            hit.add(cur);
                            damage(p, cur, dmg, true);
                            burst(cur.x, 1, cur.z, 3, 0xbfe6ff, 3);
                            px = cur.x;
                            pz = cur.z;
                            cur = nearestExcluding(px, pz, 7, hit);
                        }
                    }
                }
            }
            int au = p.lvl("aura");
            if (au > 0) {
                p.auraTimer -= dt;
                if (p.auraTimer <= 0) {
                    p.auraTimer = 0.3f;
                    aoe(p, p.x, p.z, auraRadius(p), 3 + 2 * au, e -> {
                        e.slow = Math.max(e.slow, 0.3f);
                        e.slowT = Math.max(e.slowT, 0.4f);
                    }, false);
                }
            }
        }
    }

    private void lightning(float x1, float z1, float x2, float z2) {
        Beam b = new Beam();
        b.x1 = x1; b.z1 = z1; b.x2 = x2; b.z2 = z2;
        b.width = 0.12f;
        b.life = b.max = 0.18f;
        b.lightning = true;
        b.fired = true;
        b.color = 0xbfe6ff;
        beams.add(b);
    }

    // ------------------------------------------------------------------ mermiler
    private void updateProjectiles(float dt) {
        for (int i = 0; i < projectiles.size(); i++) {
            Projectile p = projectiles.get(i);
            if (p.dead) continue;
            p.age += dt;
            if (p.kind == ProjKind.MEGA) {
                float sp = (float) Math.hypot(p.vx, p.vz), ns = Math.min(50, sp + dt * 22);
                p.vx *= ns / sp;
                p.vz *= ns / sp;
            }
            projectileTrail(p);
            float stepX = p.vx * dt, stepZ = p.vz * dt;
            p.x += stepX;
            p.z += stepZ;
            p.traveled += (float) Math.hypot(stepX, stepZ);
            boolean outside = Math.abs(p.x) > ARENA + 2 || Math.abs(p.z) > ARENA + 2;
            if (p.traveled > p.range || outside) {
                if (p.kind == ProjKind.MEGA) explodeProjectile(p);
                p.dead = true;
                continue;
            }
            if (p.enemy) {
                for (Player pl : players) {
                    if (pl.downed) continue;
                    float dx = p.x - pl.x, dz = p.z - pl.z, rr = p.radius + 0.5f;
                    if (dx * dx + dz * dz < rr * rr) {
                        hurtPlayer(pl, p.dmg);
                        burst(p.x, p.y, p.z, 6, p.color, 3);
                        p.dead = true;
                        break;
                    }
                }
                continue;
            }
            near(p.x, p.z, p.radius + 3.5f, projBuf);
            for (Enemy e : projBuf) {
                if (e.dead || (p.hit != null && p.hit.contains(e))) continue;
                float dx = e.x - p.x, dz = e.z - p.z, rr = p.radius + e.radius;
                if (dx * dx + dz * dz < rr * rr) {
                    onProjHit(p, e);
                    if (p.dead) break;
                }
            }
        }
    }

    private void explodeProjectile(Projectile p) {
        float dmg = p.dmg;
        if (p.kind == ProjKind.MEGA) {
            dmg *= 1 + Math.min(1.5f, p.traveled / 30f);
            shake(0.6f);
            explosion(p.x, p.z, p.splash * 0.6f, 0xff7a2a);
        }
        explosion(p.x, p.z, p.splash, p.kind == ProjKind.MEGA ? 0xd8344a : 0xff8ad8);
        aoe(p.owner, p.x, p.z, p.splash, dmg, null);
        p.dead = true;
    }

    private void onProjHit(Projectile p, Enemy e) {
        if (p.splash > 0) { explodeProjectile(p); return; }
        if (p.pierce > 0) {
            if (p.hit == null) p.hit = new HashSet<>();
            p.hit.add(e);
        }
        float d = p.dmg;
        if (p.markBonus > 0 && e.markT > 0) {
            e.markT = 0;
            d += p.markBonus;
            burst(e.x, 1.2f, e.z, 8, 0xffe08a, 4);
        }
        damage(p.owner, e, d, true);
        if (p.slow > 0) { e.slow = Math.max(e.slow, p.slow); e.slowT = Math.max(e.slowT, p.slowT); }
        if (p.root > 0) e.rootT = Math.max(e.rootT, p.root);
        if (p.mark) e.markT = 4f;
        if (p.knock > 0) knock(e, e.x - p.vx, e.z - p.vz, p.knock);
        burst(p.x, p.y, p.z, 3, p.color, 2.5f);
        p.pierce--;
        if (p.pierce < 0) p.dead = true;
    }

    // ------------------------------------------------------------------ tuzak, alan, zamanlayıcı
    private void updateTraps(float dt) {
        for (Trap t : traps) {
            if (t.dead) continue;
            t.age += dt;
            t.life -= dt;
            if (t.life <= 0) { t.dead = true; burst(t.x, 0.3f, t.z, 5, 0x555555, 2); continue; }
            if (t.age < t.arm) continue;
            near(t.x, t.z, 4, tmpBuf);
            boolean trig = false;
            for (Enemy e : tmpBuf) {
                if (e.dead) continue;
                float dx = e.x - t.x, dz = e.z - t.z, rr = 1.1f + e.radius;
                if (dx * dx + dz * dz < rr * rr) { trig = true; break; }
            }
            if (trig) {
                t.dead = true;
                final float root = t.root;
                aoe(t.owner, t.x, t.z, t.radius, t.dmg, e -> e.rootT = Math.max(e.rootT, root));
                explosion(t.x, t.z, t.radius, 0xff6a2a);
                shake(0.15f);
            }
        }
    }

    private void updateZones(float dt) {
        for (Zone zn : zones) {
            if (zn.dead) continue;
            zn.life -= dt;
            near(zn.x, zn.z, zn.radius + 3, tmpBuf);
            for (Enemy e : tmpBuf) {
                float dx = e.x - zn.x, dz = e.z - zn.z;
                if (dx * dx + dz * dz < zn.radius * zn.radius) {
                    e.slow = Math.max(e.slow, zn.slow);
                    e.slowT = Math.max(e.slowT, 0.2f);
                }
            }
            if (rng.nextFloat() < dt * 20) {
                double a = rng.nextDouble() * Math.PI * 2;
                float rr = (float) Math.sqrt(rng.nextFloat()) * zn.radius;
                spark(zn.x + (float) Math.cos(a) * rr, 0.2f, zn.z + (float) Math.sin(a) * rr, zn.color);
            }
            if (zn.life <= 0) {
                zn.dead = true;
                if (zn.onEnd != null) zn.onEnd.run();
            }
        }
        for (Telegraph tg : telegraphs) {
            if (tg.dead) continue;
            tg.t -= dt;
            if (tg.t <= 0) { tg.dead = true; tg.fire.run(); }
        }
        for (FallingSword s : swords) {
            if (s.t > 0) s.t -= dt;
            else { s.stuck -= dt; if (s.stuck <= 0) s.dead = true; }
        }
    }

    private void updateTimed(float dt) {
        for (int i = 0; i < timed.size(); i++) {
            Timed t = timed.get(i);
            if (t.done) continue;
            t.t -= dt;
            if (t.t <= 0) { t.done = true; t.action.run(); }
        }
    }

    // ------------------------------------------------------------------ küreler (takım tecrübesi)
    void dropOrb(float x, float z, float value, int type) {
        if (type == Orb.XP && orbs.size() > 700) {
            Orb o = orbs.get(rng.nextInt(orbs.size()));
            if (o.type == Orb.XP) { o.value += value; return; }
        }
        Orb o = new Orb();
        o.x = x; o.z = z; o.value = value; o.type = type;
        orbs.add(o);
    }

    private void updateOrbs(float dt) {
        for (Orb o : orbs) {
            if (o.value < 0) continue;
            o.age += dt;
            Player p = nearestAlive(o.x, o.z);
            if (p == null) continue;
            float dx = p.x - o.x, dz = p.z - o.z, d2 = dx * dx + dz * dz;
            if (!o.magnet && d2 < p.pickup * p.pickup) o.magnet = true;
            if (o.magnet) {
                float d = (float) Math.sqrt(d2) + 1e-4f;
                o.speed = Math.min(40, o.speed + dt * 45);
                float step = Math.min(d, (8 + o.speed) * dt);
                o.x += dx / d * step;
                o.z += dz / d * step;
                if (d < 0.7f) {
                    collect(p, o);
                    o.value = -1;
                }
            }
        }
    }

    private void collect(Player p, Orb o) {
        switch (o.type) {
            case Orb.XP -> teamXp += o.value;
            case Orb.HEAL -> {
                p.heal(p.maxHp * 0.3f);
                popText(p.x, 2.4f, p.z, "+CAN", 0xff5ad17a, 24);
                burst(p.x, 1, p.z, 10, 0x5ad17a, 3);
            }
            case Orb.MAGNET -> {
                for (Orb other : orbs) if (other.type == Orb.XP) other.magnet = true;
                popText(p.x, 2.4f, p.z, "MIKNATIS!", 0xff6aa8ff, 24);
            }
        }
    }

    // ------------------------------------------------------------------ efekt güncelle
    void updateFx(float dt) {
        updateFxVisual(dt);
        for (Beam b : beams) {
            if (!b.fired) {
                b.delay -= dt;
                if (b.delay <= 0) { b.fired = true; fireBeam(b); }
            }
        }
    }

    /** Sadece görsel (istemci de kullanır): parçacık, halka, yazı, ışın ömrü. */
    void updateFxVisual(float dt) {
        for (Particle p : particles) {
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            p.vy -= p.grav * dt;
            if (p.y < 0.05f) { p.y = 0.05f; p.vy = 0; p.vx *= 0.9f; p.vz *= 0.9f; }
        }
        for (RingFx f : rings) f.life -= dt;
        for (DmgText t : texts) { t.life -= dt; t.y += dt * 1.6f; }
        for (Beam b : beams) {
            if (b.fired) b.life -= dt;
            else if (role == Role.CLIENT) b.delay -= dt;
        }
        if (role == Role.CLIENT) {
            particles.removeIf(p -> p.life <= 0);
            rings.removeIf(f -> f.life <= 0);
            texts.removeIf(t -> t.life <= 0);
        }
    }

    private void fireBeam(Beam b) {
        float dx = b.x2 - b.x1, dz = b.z2 - b.z1, len2 = dx * dx + dz * dz;
        for (Enemy e : enemies) {
            if (e.dead) continue;
            float t = ((e.x - b.x1) * dx + (e.z - b.z1) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            float px = b.x1 + dx * t, pz = b.z1 + dz * t;
            float ex = e.x - px, ez = e.z - pz, rr = b.width / 2 + e.radius;
            if (ex * ex + ez * ez < rr * rr) {
                damage(b.owner, e, b.dmg, true);
                if (b.mark) e.markT = 4f;
            }
        }
        shake(0.5f);
        for (int i = 0; i < 40; i++) {
            float t = rng.nextFloat();
            spark(b.x1 + dx * t, 1 + rng.nextFloat(), b.z1 + dz * t, b.color);
        }
    }

    private void cleanup() {
        enemies.removeIf(e -> e.dead);
        projectiles.removeIf(p -> p.dead);
        orbs.removeIf(o -> o.value < 0);
        particles.removeIf(p -> p.life <= 0);
        rings.removeIf(f -> f.life <= 0);
        texts.removeIf(t -> t.life <= 0);
        traps.removeIf(t -> t.dead);
        zones.removeIf(z -> z.dead);
        beams.removeIf(b -> b.fired && b.life <= 0);
        timed.removeIf(t -> t.done);
        telegraphs.removeIf(t -> t.dead);
        swords.removeIf(s -> s.dead);
    }

    // ------------------------------------------------------------------ seviye atlama (herkes kendi kartını seçer)
    private void checkLevelUp() {
        while (teamXp >= xpNext) {
            teamXp -= xpNext;
            teamLevel++;
            xpNext = xpFor(teamLevel);
            pendingLevels++;
        }
        if (pendingLevels > 0 && state == State.PLAY) openLevelUp();
    }

    private void openLevelUp() {
        pickRound++;
        for (Player p : players) {
            List<Upgrade> pool = new ArrayList<>();
            for (Upgrade u : p.upIndex.values()) if (u.max < 999 && p.lvl(u.id) < u.max) pool.add(u);
            Collections.shuffle(pool, rng);
            p.choices = new ArrayList<>(pool.subList(0, Math.min(3, pool.size())));
            if (p.choices.isEmpty()) {
                Upgrade f = Upgrade.fallback();
                p.upIndex.put(f.id, f);
                p.choices.add(f);
            }
            p.picked = false;
            burst(p.x, 1, p.z, 20, 0xffd84a, 4);
        }
        state = State.LEVELUP;
        levelUpDelay = 0.3f;
    }

    void choose(Player p, int pick) {
        if (state != State.LEVELUP || p.picked || pick < 0 || pick >= p.choices.size()) return;
        Upgrade u = p.choices.get(pick);
        p.ups.merge(u.id, 1, Integer::sum);
        u.apply.accept(p);
        p.picked = true;
        finishPickIfDone();
    }

    private void finishPickIfDone() {
        for (Player q : players) if (!q.picked) return;
        pendingLevels--;
        state = State.PLAY;
        if (pendingLevels > 0) openLevelUp();
    }

    private void localLevelUpInput(float dt) {
        if (levelUpDelay > 0) { levelUpDelay -= dt; return; }
        if (player.picked || escMenu) return;
        int pick = -1;
        if (in.hit(GLFW_KEY_1)) pick = 0;
        if (in.hit(GLFW_KEY_2)) pick = 1;
        if (in.hit(GLFW_KEY_3)) pick = 2;
        if (in.click(GLFW_MOUSE_BUTTON_LEFT))
            for (int i = 0; i < player.choices.size(); i++) if (inside(cardRect(i))) pick = i;
        if (pick < 0 || pick >= player.choices.size()) return;
        if (role == Role.CLIENT) net.clientPick(pick);
        else choose(player, pick);
    }

    private float[] cardRect(int i) {
        float cw = 330, ch = 210, gap = 30, total = 3 * cw + 2 * gap;
        return new float[]{(winW - total) / 2 + i * (cw + gap), winH * 0.36f, cw, ch};
    }

    private float[] tabRect(int i) {
        float w = 230, h = 56, gap = 20, total = 3 * w + 2 * gap;
        return new float[]{(winW - total) / 2 + i * (w + gap), winH - 196, w, h};
    }

    private float[] modeRect(int i) {
        float w = 250, h = 50, gap = 20, total = 3 * w + 2 * gap;
        return new float[]{(winW - total) / 2 + i * (w + gap), winH - 120, w, h};
    }

    private float[] copyRect() { return new float[]{500, 178, 130, 40}; }

    private boolean inside(float[] rc) {
        return in.mx >= rc[0] && in.mx <= rc[0] + rc[2] && in.my >= rc[1] && in.my <= rc[1] + rc[3];
    }

    // ================================================================== çizim
    void render() {
        glViewport(0, 0, fbW, fbH);
        glClearColor(((FOG >> 16) & 255) / 255f, ((FOG >> 8) & 255) / 255f, (FOG & 255) / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        if (state == State.MENU || state == State.LOBBY) {
            renderMenu3D();
            ui.begin(winW, winH);
            if (state == State.MENU) renderMenuUi();
            else renderLobbyUi();
            renderNotice();
            return;
        }
        renderWorld();
        ui.begin(winW, winH);
        renderWorldUi();
        renderHud();
        boolean mp = role != Role.SOLO;
        switch (state) {
            case LEVELUP -> renderLevelUp();
            case PAUSE -> renderOverlay("DURAKLATILDI", "ESC / P: devam    M: ana menü", 0xffffffff);
            case DEAD -> renderOverlay("YENİLDİNİZ", stats() + "\n" + endHint(), 0xffff4a5a);
            case WIN -> renderOverlay("ZAFER!", stats() + "\n" + endHint(), 0xffffd84a);
            default -> { }
        }
        if (mp && escMenu && state != State.DEAD && state != State.WIN)
            renderOverlay("MENÜ", "Oyun arkada devam ediyor!\nESC: geri dön    M: oyundan çık", 0xffffffff);
        renderNotice();
    }

    private String endHint() {
        if (role == Role.CLIENT) return "Host yeniden başlatabilir    M: ana menü";
        return "ENTER: tekrar oyna" + (role == Role.HOST ? " (herkesle)" : "") + "    M: ana menü";
    }

    private String stats() {
        return String.format("Süre %02d:%02d   •   Öldürme %d   •   Seviye %d", (int) time / 60, (int) time % 60, kills, teamLevel);
    }

    private void renderWorld() {
        r.begin(proj, view, camPos, ARENA, FOG);

        r.mode = 1;
        r.draw(r.plane, r.at(camX, 0, camZ).scale(260, 1, 260), 0xffffff);
        r.mode = 0;

        for (float t = -ARENA; t <= ARENA; t += 8) {
            borderPost(t, -ARENA);
            borderPost(t, ARENA);
            borderPost(-ARENA, t);
            borderPost(ARENA, t);
        }
        for (Prop pr : props) if (Math.abs(pr.x - camX) < 45 && Math.abs(pr.z - camZ) < 45) renderProp(pr);

        // gölgeler ve yer efektleri (derinlik yazmadan)
        glDepthMask(false);
        r.alpha = 0.35f;
        for (Player p : players) shadow(p.x, p.z, 0.8f);
        for (Enemy e : enemies) if (visible(e.x, e.z)) shadow(e.x, e.z, e.radius * 1.1f);
        for (Player p : players) {
            int au = p.lvl("aura");
            if (au <= 0 || p.downed) continue;
            float rad = auraRadius(p);
            r.alpha = 0.10f;
            r.draw(r.disc, r.at(p.x, 0.03f, p.z).scale(rad * 2, 1, rad * 2), 0x7fd8ff, 1f);
            r.alpha = 0.45f;
            r.draw(r.ring, r.at(p.x, 0.04f, p.z).rotateY(menuAnim).scale(rad * 2, 1, rad * 2), 0x7fd8ff, 1f);
        }
        for (Player p : players) {
            if (!p.downed) continue;
            float pulse = 0.5f + 0.5f * (float) Math.sin(menuAnim * 6);
            r.alpha = 0.35f + 0.3f * pulse;
            r.draw(r.ring, r.at(p.x, 0.05f, p.z).scale(REVIVE_RANGE * 2, 1, REVIVE_RANGE * 2), 0xff3a4a, 1f);
            float k = p.reviveT / REVIVE_TIME;
            if (k > 0) {
                r.alpha = 0.45f;
                r.draw(r.disc, r.at(p.x, 0.04f, p.z).scale(REVIVE_RANGE * 2 * k, 1, REVIVE_RANGE * 2 * k), 0x5ad17a, 1f);
            }
        }
        for (Zone zn : zones) {
            float k = zn.life / zn.max;
            r.alpha = 0.18f + 0.15f * (1 - k);
            r.draw(r.disc, r.at(zn.x, 0.03f, zn.z).scale(zn.radius * 2, 1, zn.radius * 2), zn.color, 1f);
            r.alpha = 0.8f;
            r.draw(r.ring, r.at(zn.x, 0.05f, zn.z).scale(zn.radius * 2, 1, zn.radius * 2), zn.color, 1f);
            r.draw(r.ring, r.at(zn.x, 0.05f, zn.z).scale(zn.radius * 2 * (1 - k), 1, zn.radius * 2 * (1 - k)), 0xffffff, 1f);
        }
        for (Telegraph tg : telegraphs) {
            float k = 1 - tg.t / tg.max;
            r.alpha = 0.12f + 0.2f * k;
            r.draw(r.disc, r.at(tg.x, 0.03f, tg.z).scale(tg.radius * 2, 1, tg.radius * 2), tg.color, 1f);
            r.alpha = 0.8f;
            r.draw(r.ring, r.at(tg.x, 0.05f, tg.z).scale(tg.radius * 2, 1, tg.radius * 2), tg.color, 1f);
            r.draw(r.disc, r.at(tg.x, 0.04f, tg.z).scale(tg.radius * 2 * k, 1, tg.radius * 2 * k), tg.color, 1f);
        }
        for (Beam b : beams) {
            if (b.lightning || b.fired) continue;
            r.alpha = 0.35f + 0.3f * (float) Math.abs(Math.sin(menuAnim * 30));
            beamBox(b, b.width, 0.02f, b.color, 1f, 0.04f);
        }
        r.alpha = 1;
        glDepthMask(true);

        // küreler
        for (Orb o : orbs) {
            if (!visible(o.x, o.z)) continue;
            float bob = 0.45f + (float) Math.sin(o.age * 4 + o.x) * 0.12f;
            switch (o.type) {
                case Orb.XP -> {
                    int c = o.value <= 1 ? 0x47e07a : (o.value <= 5 ? 0x4aa8ff : 0xc65cff);
                    float s = o.value <= 1 ? 0.28f : (o.value <= 5 ? 0.36f : 0.48f);
                    r.draw(r.cube, r.at(o.x, bob, o.z).rotateY(o.age * 3).rotateX(0.785f).rotateZ(0.785f).scale(s), c, 0.7f);
                }
                case Orb.HEAL -> {
                    r.draw(r.sphere, r.at(o.x, bob + 0.1f, o.z).scale(0.6f), 0xff4a6a, 0.5f);
                    r.draw(r.cube, r.at(o.x, bob + 0.1f, o.z).rotateY(o.age * 2).scale(0.5f, 0.14f, 0.14f), 0xffffff, 1f);
                    r.draw(r.cube, r.at(o.x, bob + 0.1f, o.z).rotateY(o.age * 2).scale(0.14f, 0.5f, 0.14f), 0xffffff, 1f);
                }
                case Orb.MAGNET -> {
                    r.draw(r.cube, r.at(o.x, bob + 0.1f, o.z).rotateY(o.age * 2).translate(-0.18f, 0, 0).scale(0.3f, 0.5f, 0.3f), 0xff3a3a, 0.5f);
                    r.draw(r.cube, r.at(o.x, bob + 0.1f, o.z).rotateY(o.age * 2).translate(0.18f, 0, 0).scale(0.3f, 0.5f, 0.3f), 0x3a6aff, 0.5f);
                }
                default -> { }
            }
        }

        // tuzaklar (alev çiğneyiciler)
        for (Trap t : traps) {
            boolean armed = t.age >= t.arm;
            float open = armed ? 0.35f + 0.1f * (float) Math.sin(t.age * 8) : 0.05f;
            r.draw(r.cyl, r.at(t.x, 0.12f, t.z).scale(0.9f, 0.2f, 0.9f), 0x3a3f4a);
            r.draw(r.cyl, r.at(t.x, 0.26f, t.z).rotateX(-open).scale(0.85f, 0.12f, 0.85f), 0x55606e);
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3 + 0.3;
                r.draw(r.cone, r.at(t.x + (float) Math.cos(a) * 0.36f, 0.32f, t.z + (float) Math.sin(a) * 0.36f).scale(0.1f, 0.2f, 0.1f), 0xff8a3a, 0.4f);
            }
            r.draw(r.sphere, r.at(t.x, 0.42f, t.z).scale(0.2f), armed && ((int) (t.age * 6)) % 2 == 0 ? 0xff2a2a : 0x802020, 1f);
        }

        for (Enemy e : enemies) if (visible(e.x, e.z)) renderEnemy(e);

        // oyuncular
        for (Player p : players) {
            if (p.downed) {
                base.translation(p.x, 0.35f, p.z).rotateY(p.face).rotateX(-Champion.HALF_PI);
                r.flash = 0.35f;
                p.champ.render(r, base, p.champ.anim, false);
            } else {
                base.translation(p.x, 0, p.z).rotateY(p.face);
                r.flash = p.hurtFlash > 0 ? 0.6f : 0;
                p.champ.render(r, base, p.champ.anim, p.moving);
            }
            r.flash = 0;
        }

        // dönen kılıçlar
        for (Player p : players) {
            int bl = p.lvl("blades");
            if (bl <= 0 || p.downed) continue;
            int n = 1 + bl;
            float rad = 3.0f * p.area;
            float baseAng = role == Role.CLIENT ? menuAnim * 3.2f : p.bladeAngle;
            for (int k = 0; k < n; k++) {
                double a = baseAng + k * Math.PI * 2 / n;
                float bx = p.x + (float) Math.cos(a) * rad, bz = p.z + (float) Math.sin(a) * rad;
                r.draw(r.cube, r.at(bx, 1.0f, bz).rotateY((float) -a).scale(0.12f, 0.08f, 1.1f), 0xd8e0ea, 0.5f);
                r.draw(r.cube, r.at(bx, 1.0f, bz).rotateY((float) -a).translate(0, 0, -0.5f).scale(0.4f, 0.1f, 0.1f), 0xe0b23a);
            }
        }

        // düşen dev kılıçlar
        for (FallingSword s : swords) {
            float h = s.t > 0 ? 14 * (s.t / s.max) : 0;
            base.translation(s.x, 3.6f + h, s.z).rotateY(0.6f);
            r.draw(r.cube, r.m(base).scale(0.7f, 7.5f, 0.22f), 0xd8e0ea, 0.35f);
            r.draw(r.cube, r.m(base).translate(0, 3.9f, 0).scale(2.6f, 0.4f, 0.5f), 0xe0b23a, 0.2f);
            r.draw(r.cyl, r.m(base).translate(0, 4.8f, 0).scale(0.35f, 1.6f, 0.35f), 0x4a3020);
            r.draw(r.sphere, r.m(base).translate(0, 5.7f, 0).scale(0.6f), 0xe0b23a, 0.4f);
        }

        // mermiler
        for (Projectile pr : projectiles) {
            if (!visible(pr.x, pr.z)) continue;
            float a = (float) Math.atan2(pr.vx, pr.vz);
            switch (pr.kind) {
                case BULLET -> r.draw(r.cube, r.at(pr.x, pr.y, pr.z).rotateY(a).scale(0.13f, 0.13f, 0.6f), pr.color, 1f);
                case ROCKET -> {
                    r.draw(r.cyl, r.at(pr.x, pr.y, pr.z).rotateY(a).rotateX(Champion.HALF_PI).scale(0.24f, 0.75f, 0.24f), pr.color, 0.2f);
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).rotateY(a).translate(0, 0, 0.38f).scale(0.26f), 0xff4fa3, 0.6f);
                }
                case ZAP -> r.draw(r.cube, r.at(pr.x, pr.y, pr.z).rotateY(a).scale(0.3f, 0.3f, 2.4f), pr.color, 1f);
                case MEGA -> {
                    r.draw(r.cyl, r.at(pr.x, pr.y, pr.z).rotateY(a).rotateX(Champion.HALF_PI).scale(0.7f, 2.4f, 0.7f), pr.color, 0.2f);
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).rotateY(a).translate(0, 0, 1.2f).scale(0.72f, 0.72f, 0.9f), 0xf0f0f0);
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).rotateY(a).translate(0.22f, 0.2f, 1.45f).scale(0.15f), 0xff2a2a, 1f);
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).rotateY(a).translate(-0.22f, 0.2f, 1.45f).scale(0.15f), 0xff2a2a, 1f);
                    for (int i = 0; i < 4; i++)
                        r.draw(r.cube, r.at(pr.x, pr.y, pr.z).rotateY(a).rotateZ(i * Champion.HALF_PI).translate(0, 0.5f, -1.0f).scale(0.08f, 0.6f, 0.6f), 0x2fb39a);
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).rotateY(a).translate(0, 0, -1.35f).scale(0.6f + 0.2f * rng.nextFloat()), 0xffb03a, 1f);
                }
                case ORB -> r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).scale(0.5f), pr.color, 1f);
                case BIND -> {
                    r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).scale(0.65f), pr.color, 1f);
                    r.draw(r.ring, r.at(pr.x, pr.y, pr.z).rotateY(a).rotateX(Champion.HALF_PI).rotateY(pr.age * 10).scale(1.3f), 0xe8c25a, 1f);
                }
                case SPIT -> r.draw(r.sphere, r.at(pr.x, pr.y, pr.z).scale(0.5f), pr.color, 0.9f);
            }
        }

        // kalkan balonları
        glDepthMask(false);
        for (Player p : players) {
            if (p.shield <= 0 || p.downed) continue;
            r.alpha = 0.22f + 0.05f * (float) Math.sin(menuAnim * 8);
            r.draw(r.sphere, r.at(p.x, 1.1f, p.z).scale(2.5f), p.champ.shieldColor(), 1f);
        }
        r.alpha = 1;

        // eklemeli (parlayan) efektler
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        for (Particle pt : particles) {
            if (!visible(pt.x, pt.z)) continue;
            r.alpha = Math.max(0, pt.life / pt.max);
            float s = pt.size * (0.5f + 0.5f * r.alpha);
            r.draw(r.cube, r.at(pt.x, pt.y, pt.z).rotateY(pt.life * 9).scale(s), pt.color, 1f);
        }
        for (RingFx f : rings) {
            float k = 1 - f.life / f.max;
            float rad = f.r0 + (f.r1 - f.r0) * (1 - (1 - k) * (1 - k));
            r.alpha = Math.max(0, 1 - k);
            r.draw(r.ring, r.at(f.x, 0.1f, f.z).scale(rad * 2, 1, rad * 2), f.color, 1f);
        }
        for (Beam b : beams) {
            if (!b.fired) continue;
            float k = Math.max(0, b.life / b.max);
            r.alpha = k;
            if (b.lightning) {
                float mx = (b.x1 + b.x2) / 2 + (rng.nextFloat() - .5f) * 1.2f, mz = (b.z1 + b.z2) / 2 + (rng.nextFloat() - .5f) * 1.2f;
                segment(b.x1, b.z1, mx, mz, 1.1f, 0.12f, b.color);
                segment(mx, mz, b.x2, b.z2, 1.1f, 0.12f, b.color);
            } else {
                beamBox(b, b.width * (0.4f + 0.6f * k), 1.0f, b.color, 1f, 1.2f);
                beamBox(b, b.width * 0.35f * k, 1.1f, 0xffffff, 1f, 1.2f);
            }
        }
        r.alpha = 1;
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
    }

    private boolean visible(float x, float z) { return Math.abs(x - camX) < 42 && z - camZ < 22 && z - camZ > -48; }

    private void shadow(float x, float z, float rad) {
        r.draw(r.disc, r.at(x, 0.02f, z).scale(rad * 2, 1, rad * 2), 0x000000, 0f);
    }

    private void beamBox(Beam b, float width, float y, int color, float emissive, float height) {
        float dx = b.x2 - b.x1, dz = b.z2 - b.z1, len = (float) Math.hypot(dx, dz);
        r.draw(r.cube, r.at((b.x1 + b.x2) / 2, y, (b.z1 + b.z2) / 2).rotateY((float) Math.atan2(dx, dz)).scale(width, height, len), color, emissive);
    }

    private void segment(float x1, float z1, float x2, float z2, float y, float w, int color) {
        float dx = x2 - x1, dz = z2 - z1, len = (float) Math.hypot(dx, dz);
        r.draw(r.cube, r.at((x1 + x2) / 2, y, (z1 + z2) / 2).rotateY((float) Math.atan2(dx, dz)).scale(w, w, len), color, 1f);
    }

    private void borderPost(float x, float z) {
        if (Math.abs(x - camX) > 45 || Math.abs(z - camZ) > 45) return;
        r.draw(r.cyl, r.at(x, 1.5f, z).scale(0.8f, 3f, 0.8f), 0x3b2a4a);
        r.draw(r.sphere, r.at(x, 3.3f + (float) Math.sin(menuAnim * 2 + x + z) * 0.15f, z).scale(0.7f), 0xff3fa0, 1f);
    }

    private void renderProp(Prop p) {
        float s = p.scale;
        base.translation(p.x, 0, p.z).rotateY(p.rot).scale(s);
        switch (p.type) {
            case 0 -> {
                r.draw(r.sphere, r.m(base).translate(0, 0.3f, 0).scale(2.2f, 1.2f, 1.7f), 0x4b5563);
                r.draw(r.sphere, r.m(base).translate(0.9f, 0.2f, 0.5f).scale(1.1f, 0.8f, 1f), 0x5a6474);
            }
            case 1 -> {
                r.draw(r.cone, r.m(base).translate(0, 1.0f, 0).scale(0.6f, 2.2f, 0.6f), 0x3fd4e8, 0.6f);
                r.draw(r.cone, r.m(base).translate(0.5f, 0.6f, 0.2f).rotateZ(-0.4f).scale(0.4f, 1.4f, 0.4f), 0x7f6aff, 0.6f);
                r.draw(r.cone, r.m(base).translate(-0.4f, 0.5f, -0.2f).rotateZ(0.5f).scale(0.35f, 1.1f, 0.35f), 0x3fd4e8, 0.6f);
            }
            case 2 -> {
                r.draw(r.cyl, r.m(base).translate(0, 1f, 0).scale(0.4f, 2f, 0.4f), 0x5a3b26);
                r.draw(r.cone, r.m(base).translate(0, 2.6f, 0).scale(2.4f, 2.2f, 2.4f), 0x2f6b3f);
                r.draw(r.cone, r.m(base).translate(0, 3.6f, 0).scale(1.7f, 1.7f, 1.7f), 0x3a7f4a);
            }
            default -> {
                r.draw(r.cyl, r.m(base).translate(0, 1.3f, 0).scale(1.0f, 2.6f, 1.0f), 0x6b6f80);
                r.draw(r.cube, r.m(base).translate(0, 0.15f, 0).scale(1.5f, 0.3f, 1.5f), 0x5b5f70);
                r.draw(r.cube, r.m(base).translate(0.9f, 0.25f, 0.4f).rotateY(0.7f).rotateZ(0.3f).scale(0.8f, 0.5f, 0.6f), 0x6b6f80);
            }
        }
    }

    private void renderEnemy(Enemy e) {
        float sc = e.radius / e.kind.radius * Math.min(1, 0.3f + e.spawnT * 2.5f);
        float bob = Math.abs((float) Math.sin(e.anim * 9)) * 0.08f;
        base.translation(e.x, 0, e.z).rotateY(e.face).scale(sc);
        r.flash = e.flash > 0 ? 0.75f : 0;
        int col = e.kind.color;
        if (e.slowT > 0) col = mix(col, 0x7fd8ff, 0.4f);
        int eye = e.elite ? 0xffd84a : 0xfff04a;
        switch (e.kind) {
            case MINION -> {
                r.draw(r.sphere, r.m(base).translate(0, 0.55f + bob, 0).scale(1.1f, 1.0f, 1.1f), col);
                for (int i = -1; i <= 1; i++)
                    r.draw(r.cone, r.m(base).translate(i * 0.25f, 1.05f + bob, -0.1f).rotateZ(-i * 0.4f).scale(0.2f, 0.45f, 0.2f), mix(col, 0x000000, 0.35f));
                r.draw(r.sphere, r.m(base).translate(-0.18f, 0.7f + bob, 0.44f).scale(0.16f), eye, 1f);
                r.draw(r.sphere, r.m(base).translate(0.18f, 0.7f + bob, 0.44f).scale(0.16f), eye, 1f);
                r.draw(r.cube, r.m(base).translate(0, 0.42f + bob, 0.5f).scale(0.3f, 0.05f, 0.05f), 0x1a0a20);
            }
            case RUNNER -> {
                float leg = (float) Math.sin(e.anim * 18) * 0.7f;
                r.draw(r.cone, r.m(base).translate(0, 0.75f, 0).rotateX(1.1f).scale(0.7f, 1.3f, 0.6f), col);
                r.draw(r.cyl, r.m(base).translate(-0.2f, 0.45f, 0).rotateX(leg).translate(0, -0.22f, 0).scale(0.1f, 0.45f, 0.1f), 0x40201a);
                r.draw(r.cyl, r.m(base).translate(0.2f, 0.45f, 0).rotateX(-leg).translate(0, -0.22f, 0).scale(0.1f, 0.45f, 0.1f), 0x40201a);
                r.draw(r.sphere, r.m(base).translate(-0.13f, 0.85f, 0.45f).scale(0.12f), eye, 1f);
                r.draw(r.sphere, r.m(base).translate(0.13f, 0.85f, 0.45f).scale(0.12f), eye, 1f);
                r.draw(r.cone, r.m(base).translate(0, 1.15f, -0.3f).rotateX(-0.8f).scale(0.15f, 0.5f, 0.15f), mix(col, 0xffd84a, 0.4f));
            }
            case BRUTE -> {
                float arm = (float) Math.sin(e.anim * 5) * 0.4f;
                r.draw(r.cube, r.m(base).translate(0, 1.0f + bob, 0).scale(1.7f, 1.5f, 1.3f), col);
                r.draw(r.cube, r.m(base).translate(0, 1.55f + bob, 0.45f).scale(1.0f, 0.5f, 0.6f), mix(col, 0x000000, 0.3f));
                r.draw(r.cone, r.m(base).translate(-0.55f, 2.0f + bob, 0.2f).rotateZ(0.5f).scale(0.25f, 0.7f, 0.25f), 0xe8e0d0);
                r.draw(r.cone, r.m(base).translate(0.55f, 2.0f + bob, 0.2f).rotateZ(-0.5f).scale(0.25f, 0.7f, 0.25f), 0xe8e0d0);
                r.draw(r.sphere, r.m(base).translate(-0.25f, 1.6f + bob, 0.76f).scale(0.18f), 0xff4a2a, 1f);
                r.draw(r.sphere, r.m(base).translate(0.25f, 1.6f + bob, 0.76f).scale(0.18f), 0xff4a2a, 1f);
                r.draw(r.sphere, r.m(base).translate(-1.0f, 0.7f, 0.2f + arm).scale(0.7f), mix(col, 0x000000, 0.2f));
                r.draw(r.sphere, r.m(base).translate(1.0f, 0.7f, 0.2f - arm).scale(0.7f), mix(col, 0x000000, 0.2f));
            }
            case SPITTER -> {
                r.draw(r.sphere, r.m(base).translate(0, 0.65f + bob, 0).scale(1.2f, 1.1f, 1.2f), col);
                r.draw(r.cyl, r.m(base).translate(0, 0.7f + bob, 0.55f).rotateX(Champion.HALF_PI).scale(0.4f, 0.5f, 0.4f), mix(col, 0x000000, 0.4f));
                r.draw(r.sphere, r.m(base).translate(0, 0.7f + bob, 0.82f).scale(0.3f), 0x8cff5a, e.atkCd < 0.6f ? 1f : 0.3f);
                r.draw(r.sphere, r.m(base).translate(0, 1.15f + bob, 0.3f).scale(0.25f), eye, 1f);
                for (int i = 0; i < 3; i++) {
                    double a = i * Math.PI * 2 / 3 + e.anim;
                    r.draw(r.cyl, r.m(base).translate((float) Math.cos(a) * 0.45f, 0.15f, (float) Math.sin(a) * 0.45f).scale(0.12f, 0.3f, 0.12f), 0x2a5a2a);
                }
            }
            case BOSS -> {
                float arm = (float) Math.sin(e.anim * 2) * 0.5f;
                boolean wind = e.windT > 0;
                r.draw(r.sphere, r.m(base).translate(0, 2.6f + bob * 2, 0).scale(4.6f, 4.2f, 4.2f), col);
                r.draw(r.sphere, r.m(base).translate(0, 2.2f + bob * 2, 1.2f).scale(3.0f, 2.0f, 2.2f), mix(col, 0x000000, 0.3f));
                for (int i = 0; i < 7; i++) {
                    double a = -1.2 + i * 0.4;
                    r.draw(r.cone, r.m(base).translate((float) Math.sin(a) * 1.6f, 4.6f + bob * 2, (float) Math.cos(a) * 0.4f - 0.3f).rotateZ((float) -a * 0.5f).scale(0.4f, 1.5f, 0.4f), 0xff3fa0, 0.6f);
                }
                r.draw(r.sphere, r.m(base).translate(-0.8f, 3.1f + bob * 2, 2.0f).scale(0.6f), wind ? 0xff2a2a : 0xff3fa0, 1f);
                r.draw(r.sphere, r.m(base).translate(0.8f, 3.1f + bob * 2, 2.0f).scale(0.6f), wind ? 0xff2a2a : 0xff3fa0, 1f);
                r.draw(r.cube, r.m(base).translate(0, 1.9f + bob * 2, 2.1f).scale(1.6f, 0.25f, 0.2f), 0x1a0520);
                r.draw(r.sphere, r.m(base).translate(-2.6f, 1.6f, 0.5f + arm).scale(1.8f), mix(col, 0x000000, 0.2f));
                r.draw(r.sphere, r.m(base).translate(2.6f, 1.6f, 0.5f - arm).scale(1.8f), mix(col, 0x000000, 0.2f));
                for (int i = 0; i < 5; i++)
                    r.draw(r.cube, r.m(base).translate(-1.5f + i * 0.75f, 2.4f + (i % 2) * 0.8f, 2.05f - Math.abs(i - 2) * 0.35f).scale(0.12f, 0.5f, 0.05f), 0xff4fe0, 1f);
            }
        }
        if (e.elite) {
            float top = e.kind == EnemyKind.BRUTE ? 2.4f : 1.5f;
            r.draw(r.cyl, r.m(base).translate(0, top + bob, 0).scale(0.7f, 0.2f, 0.7f), 0xffd84a, 0.6f);
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5;
                r.draw(r.cone, r.m(base).translate((float) Math.cos(a) * 0.28f, top + 0.2f + bob, (float) Math.sin(a) * 0.28f).scale(0.12f, 0.25f, 0.12f), 0xffd84a, 0.6f);
            }
        }
        r.flash = 0;
        if (e.rootT > 0) {
            r.draw(r.ring, r.at(e.x, 0.3f, e.z).rotateY(e.anim * 3).scale(e.radius * 2.4f, 1, e.radius * 2.4f), 0x9ff0ff, 1f);
            r.draw(r.ring, r.at(e.x, 0.7f, e.z).rotateY(-e.anim * 3).scale(e.radius * 2.2f, 1, e.radius * 2.2f), 0x9ff0ff, 1f);
        }
        if (e.markT > 0) {
            r.draw(r.sphere, r.at(e.x, e.radius * 2 + 1.0f + (float) Math.sin(e.anim * 6) * 0.1f, e.z).scale(0.3f), 0xffe08a, 1f);
        }
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------ menü 3D
    private void renderMenu3D() {
        float aspect = fbW / (float) Math.max(1, fbH);
        proj.setPerspective((float) Math.toRadians(40), aspect, 0.3f, 200f);
        float camOff = -1.7f;
        camPos.set(camOff, 2.4f, 6.4f);
        view.setLookAt(camPos.x, camPos.y, camPos.z, camOff, 1.0f, 0, 0, 1, 0);
        r.begin(proj, view, camPos, ARENA, FOG);
        r.mode = 1;
        r.draw(r.plane, r.at(0, 0, 0).scale(200, 1, 200), 0xffffff);
        r.mode = 0;
        Champion c = previews[menuSel];
        r.draw(r.cyl, r.at(0, 0.1f, 0).scale(3.2f, 0.2f, 3.2f), 0x2a3346);
        glDepthMask(false);
        r.alpha = 0.8f;
        r.draw(r.ring, r.at(0, 0.22f, 0).rotateY(menuAnim).scale(3.3f, 1, 3.3f), c.color, 1f);
        r.alpha = 1;
        glDepthMask(true);
        base.translation(0, 0.2f, 0).rotateY(menuAnim * 0.7f);
        c.render(r, base, menuAnim, false);
        for (int i = 0; i < 6; i++) {
            Enemy e = new Enemy();
            e.kind = i % 3 == 0 ? EnemyKind.BRUTE : EnemyKind.MINION;
            e.radius = e.kind.radius;
            double a = Math.PI * 0.75 + i * 0.35;
            e.x = (float) Math.cos(a) * 9 + 3;
            e.z = -(float) Math.abs(Math.sin(a)) * 9 - 4;
            e.anim = menuAnim + i;
            e.spawnT = 10;
            e.face = (float) Math.atan2(-e.x, -e.z);
            renderEnemy(e);
        }
    }

    // ================================================================== arayüz
    private void renderMenuUi() {
        float W = winW, H = winH;
        Champion c = previews[menuSel];
        int cc = 0xff000000 | c.color;
        ui.textS("RİFT SÜRÜSÜ", 40, 30, 64, 0xffff4fa3);
        ui.textS("League of Legends: Swarm parodisi — hayatta kal, güçlen, Void Titanı'nı yen (tek başına veya 4 kişiye kadar co-op)", 44, 104, 18, 0xffc8d0e0);

        float px = 40, py = 160, pw = Math.min(560, W * 0.42f);
        ui.rect(px - 16, py - 16, pw + 32, H - py - 230, 0xb0101624);
        ui.rect(px - 16, py - 16, 6, H - py - 230, cc);
        ui.textS(c.name, px, py, 46, cc);
        ui.textS(c.title, px, py + 54, 20, 0xffb8c2d8);
        float y = py + 92;
        for (String line : ui.wrap(c.desc, pw, 17)) { ui.textS(line, px, y, 17, 0xffe8ecf4); y += 24; }
        y += 10;
        for (int i = 0; i < 4; i++) {
            ui.rect(px, y, 30, 30, cc);
            ui.text(KEYS[i], px + 8, y + 2, 22, 0xff101624);
            ui.textS(c.abil[i], px + 42, y + 3, 19, 0xffffffff);
            y += 32;
            for (String line : ui.wrap(c.abilDesc[i], pw - 42, 15)) { ui.text(line, px + 42, y, 15, 0xffaab4c8); y += 20; }
            y += 6;
        }
        ui.text(String.format("Can: %.0f   Hız: %.1f", c.baseHp, c.baseSpeed), px, y + 2, 16, 0xff8fa0bc);

        for (int i = 0; i < 3; i++) {
            float[] rc = tabRect(i);
            boolean sel = i == menuSel, hov = inside(rc);
            int col = 0xff000000 | previews[i].color;
            ui.rect(rc[0], rc[1], rc[2], rc[3], sel ? 0xf0253048 : (hov ? 0xd01c2438 : 0xc0121828));
            ui.frame(rc[0], rc[1], rc[2], rc[3], sel ? 3 : 1, sel ? col : 0xff40485c);
            ui.textC((i + 1) + ". " + previews[i].name, rc[0] + rc[2] / 2, rc[1] + 14, 22, sel ? col : 0xffd0d6e0);
        }
        String[] modes = {"ENTER  Tek oyuncu", "H  Oda kur (host)", "J  Odaya katıl"};
        for (int i = 0; i < 3; i++) {
            float[] rc = modeRect(i);
            boolean hov = inside(rc);
            ui.rect(rc[0], rc[1], rc[2], rc[3], hov ? 0xf0303a58 : 0xe0182034);
            ui.frame(rc[0], rc[1], rc[2], rc[3], 2, i == 0 ? 0xffff4fa3 : 0xff7fe0ff);
            ui.textC(modes[i], rc[0] + rc[2] / 2, rc[1] + 13, 20, 0xffffffff);
        }
        ui.textC("← → / A D: şampiyon seç", W / 2, H - 52, 16, 0x90ffffff);
    }

    private void renderLobbyUi() {
        float W = winW, H = winH;
        boolean host = role == Role.HOST;
        float px = 40, pw = Math.min(W * 0.55f, 820);
        ui.rect(px - 16, 24, pw + 32, H - 48, 0xd0101624);
        ui.textS(host ? "ODA KUR (HOST)" : "ODAYA KATIL", px, 36, 44, host ? 0xffff4fa3 : 0xff7fe0ff);
        ui.text("Şampiyonun: " + previews[menuSel].name, px, 90, 18, 0xff000000 | previews[menuSel].color);

        String code = net != null ? net.myCode() : null;
        float y = 140;
        ui.text(host ? "1) Oda kodunu arkadaşına gönder:" : "1) Kendi kodunu host'a gönder:", px, y, 18, 0xffd0d6e0);
        y += 32;
        ui.rect(px, y, 440, 52, 0xff1a2236);
        ui.frame(px, y, 440, 52, 2, 0xff3a4a6a);
        ui.textS(code != null ? code : "...", px + 16, y + 6, 38, code != null ? 0xffffffff : 0xff808898);
        float[] hit = copyRect();
        ui.rect(hit[0], hit[1], hit[2], hit[3], inside(hit) ? 0xff3a5a8a : 0xff2a3a5a);
        ui.textC("Kopyala", hit[0] + hit[2] / 2, hit[1] + 9, 18, 0xffffffff);
        y += 60;
        if (net != null) {
            String st = net.net.stunStatus;
            if (!"hazır".equals(st)) { ui.text(st, px, y, 15, 0xffffb347); y += 22; }
            ui.text("Doğrudan adres: " + net.publicStr() + "     Yerel ağ: " + net.localStr(), px, y, 15, 0xff8fa0bc);
            y += 22;
            ui.text("(Kopyalama: Cmd/Ctrl + C)", px, y, 13, 0x90ffffff);
            y += 34;
        }

        ui.text(host ? "2) Arkadaşının kodunu buraya yapıştır (Cmd/Ctrl + V) ve ENTER:" : "2) Host'un kodunu buraya yapıştır (Cmd/Ctrl + V) ve ENTER:", px, y, 18, 0xffd0d6e0);
        y += 32;
        ui.rect(px, y, pw, 48, 0xff0c111c);
        ui.frame(px, y, pw, 48, 2, 0xff7fe0ff);
        String t = in.typed.toString();
        boolean caret = ((int) (menuAnim * 2)) % 2 == 0;
        ui.text(t + (caret ? "|" : ""), px + 14, y + 10, 24, 0xffffffff);
        y += 62;
        if (net != null) {
            for (String line : ui.wrap(net.status, pw, 17)) { ui.text(line, px, y, 17, 0xffffd84a); y += 24; }
        }
        y += 10;
        ui.text("Oyuncular:", px, y, 20, 0xffffffff);
        y += 30;
        if (players.isEmpty()) { ui.text("(henüz bağlanılmadı)", px + 10, y, 17, 0xff808898); y += 26; }
        for (Player p : players) {
            int c = 0xff000000 | p.champ.color;
            ui.rect(px + 10, y + 4, 12, 12, c);
            String me = (host && p.local) || (!host && net != null && p.id == net.myId) ? "  (sen)" : "";
            ui.text(p.displayName() + me, px + 30, y, 18, c);
            y += 26;
        }
        y = Math.max(y + 10, H - 150);
        String help = host
                ? "3) Arkadaşın bağlanınca kutu BOŞKEN ENTER ile oyunu başlat.  ESC: geri"
                : "3) Bağlanınca host'un başlatmasını bekle.  ESC: geri";
        ui.text(help, px, y, 17, 0xffc8d0e0);
        y += 26;
        for (String line : ui.wrap("İpucu: İkiniz de birbirinizin kodunu girin (delik açma iki taraftan çalışır). Bağlanmazsa host, modeminde UDP "
                + Net.DEFAULT_PORT + " portunu bilgisayarına yönlendirsin; o zaman sadece katılan kişinin host kodunu girmesi yeter.", pw, 14)) {
            ui.text(line, px, y, 14, 0xff8fa0bc);
            y += 19;
        }
    }

    private void renderNotice() {
        if (noticeT > 0 && notice != null) {
            int a = (int) (Math.min(1, noticeT) * 255);
            float w = ui.width(notice, 18) + 40;
            ui.rect(winW / 2f - w / 2, winH - 44, w, 32, (a * 3 / 4) << 24);
            ui.textC(notice, winW / 2f, winH - 40, 18, (a << 24) | 0xffd84a);
        }
    }

    private void renderWorldUi() {
        for (DmgText t : texts) {
            float[] s = project(t.x, t.y, t.z);
            if (s == null) continue;
            int a = (int) (Math.max(0, Math.min(1, t.life / 0.4f)) * 255);
            ui.textC(t.s, s[0], s[1], t.size, (a << 24) | (t.color & 0xffffff));
        }
        for (Enemy e : enemies) {
            if (e.dead || e.kind == EnemyKind.BOSS || e.hp >= e.maxHp) continue;
            if (!e.elite && e.kind != EnemyKind.BRUTE && e.kind != EnemyKind.SPITTER) continue;
            float[] s = project(e.x, e.radius * 2.2f + 1.0f, e.z);
            if (s == null) continue;
            float w = e.elite ? 70 : 44;
            ui.rect(s[0] - w / 2, s[1], w, 6, 0xc0000000);
            ui.rect(s[0] - w / 2, s[1], w * Math.max(0, e.hp / e.maxHp), 6, e.elite ? 0xffffd84a : 0xffff5a5a);
        }
        boolean mp = players.size() > 1;
        for (Player p : players) {
            float[] s = project(p.x, p.downed ? 1.4f : 2.6f, p.z);
            if (s == null) continue;
            if (mp) ui.textC(p.label + " " + p.champ.name, s[0], s[1] - 22, 14, p.local ? 0xffffffff : (0xff000000 | p.champ.color));
            if (p.downed) {
                ui.textC("KALDIR!", s[0], s[1] + 10, 16, 0xffff4a5a);
                continue;
            }
            ui.rect(s[0] - 36, s[1], 72, 7, 0xc0000000);
            ui.rect(s[0] - 36, s[1], 72 * Math.max(0, p.hp / p.maxHp), 7, p.local ? 0xff5ad17a : 0xff7fb0ff);
            if (p.shield > 0) ui.rect(s[0] - 36, s[1] - 4, Math.min(72, 72 * p.shield / p.maxHp), 3, 0xffffffff);
        }
    }

    private float[] project(float x, float y, float z) {
        v4.set(x, y, z, 1).mul(pv);
        if (v4.w <= 0.01f) return null;
        return new float[]{(v4.x / v4.w * 0.5f + 0.5f) * winW, (1 - (v4.y / v4.w * 0.5f + 0.5f)) * winH};
    }

    private boolean bossAlive() { return role == Role.CLIENT ? bossAliveNet : boss != null && !boss.dead; }

    private float bossFrac() { return role == Role.CLIENT ? bossFracNet : (boss != null ? boss.hp / boss.maxHp : 0); }

    private void renderHud() {
        float W = winW, H = winH;
        Player p = player;
        Champion ch = p.champ;
        ui.rect(0, 0, W, 12, 0xd00b0f1a);
        ui.rect(0, 0, W * Math.min(1, teamXp / Math.max(1, xpNext)), 12, 0xff3fa9ff);
        ui.textS("Sv " + teamLevel, 14, 18, 26, 0xffffffff);
        ui.textC(String.format("%02d:%02d", (int) time / 60, (int) time % 60), W / 2, 18, 38, 0xffffffff);
        ui.textC("Öldürme: " + kills, W / 2, 62, 18, 0xffd0d6e0);
        if (!bossSpawned) {
            float left = Math.max(0, BOSS_TIME - time);
            ui.textS(String.format("Boss: %d:%02d", (int) left / 60, (int) left % 60), W - 150, 18, 18, 0xffb07aff);
        }
        if (role != Role.SOLO) {
            float ty = 48;
            for (Player q : players) {
                if (q == p) continue;
                int c = 0xff000000 | q.champ.color;
                ui.textS(q.displayName(), W - 220, ty, 15, c);
                ui.rect(W - 220, ty + 20, 200, 8, 0xc0000000);
                if (q.downed) ui.text("DÜŞTÜ", W - 214, ty + 16, 13, 0xffff4a5a);
                else ui.rect(W - 220, ty + 20, 200 * Math.max(0, q.hp / q.maxHp), 8, 0xff7fb0ff);
                ty += 40;
            }
        }
        if (bossAlive()) {
            float bw = W * 0.5f, x = (W - bw) / 2, y = 96;
            ui.textC("VOID TİTANI", W / 2, y - 4, 20, 0xffff4fa3);
            ui.rect(x, y + 22, bw, 16, 0xd0000000);
            ui.rect(x, y + 22, bw * bossFrac(), 16, 0xffc83a8a);
            ui.frame(x, y + 22, bw, 16, 2, 0xffff4fa3);
        }
        if (bannerT > 0 && banner != null) {
            int a = (int) (Math.min(1, bannerT / 0.5f) * 255);
            ui.textC(banner, W / 2, H * 0.22f, 40, (a << 24) | (bannerColor & 0xffffff));
        }

        float size = 64, gap = 12, total = 4 * size + 3 * gap, x0 = (W - total) / 2, y0 = H - size - 34;
        int cc = 0xff000000 | ch.color;
        for (int i = 0; i < 4; i++) {
            float x = x0 + i * (size + gap);
            boolean ready = ch.cd[i] <= 0 && !p.downed;
            ui.rect(x, y0, size, size, 0xe0151b2a);
            ui.rect(x + 4, y0 + 4, size - 8, size - 8, ready ? (0x60000000 | (ch.color & 0xffffff)) : 0x40303848);
            ui.textC(KEYS[i], x + size / 2, y0 + 12, 32, ready ? 0xffffffff : 0xff8090a0);
            if (ch.cd[i] > 0) {
                float frac = Math.min(1, ch.cd[i] / Math.max(0.01f, ch.effCd(i)));
                ui.rect(x, y0, size, size * frac, 0xa0000000);
                ui.textC(String.format("%.0f", Math.ceil(ch.cd[i])), x + size / 2, y0 + size - 26, 18, 0xffffd84a);
            }
            ui.frame(x, y0, size, size, 2, ready ? cc : 0xff3a4252);
            String n = ch.abil[i];
            float fs = 12;
            while (ui.width(n, fs) > size + gap - 2 && fs > 8) fs -= 0.5f;
            ui.textC(n, x + size / 2, y0 + size + 6, fs, 0xffc0c8d8);
        }
        float hbY = y0 - 30;
        ui.rect(x0, hbY, total, 20, 0xd0000000);
        float hpF = Math.max(0, p.hp / p.maxHp);
        ui.rect(x0, hbY, total * hpF, 20, hpF > 0.35f ? 0xff3fbf6a : 0xffe0503a);
        if (p.shield > 0) ui.rect(x0, hbY, Math.min(total, total * p.shield / p.maxHp), 6, 0xe0ffffff);
        ui.frame(x0, hbY, total, 20, 2, 0xff3a4252);
        ui.textC(Math.round(Math.max(0, p.hp)) + " / " + Math.round(p.maxHp), W / 2, hbY + 1, 16, 0xffffffff);
        String st = ch.status();
        if (st != null && !p.downed) ui.textC(st, W / 2, hbY - 30, 20, cc);
        ui.textS(ch.name, x0 - ui.width(ch.name, 22) - 20, y0 + 18, 22, cc);

        float y = 56;
        for (Map.Entry<String, Integer> en : p.ups.entrySet()) {
            Upgrade u = p.upIndex.get(en.getKey());
            if (u == null) continue;
            ui.rect(14, y + 3, 10, 10, u.color);
            ui.textS(u.name + "  " + en.getValue(), 30, y, 15, 0xffd8dee8);
            y += 21;
        }
        if (p.downed && state == State.PLAY) {
            ui.textC("DÜŞTÜN!", W / 2, H * 0.4f, 56, 0xffff4a5a);
            ui.textC("Bir takım arkadaşın yanına gelip " + (int) REVIVE_TIME + " sn durursa ayağa kalkarsın", W / 2, H * 0.4f + 70, 20, 0xffffffff);
            float k = p.reviveT / REVIVE_TIME;
            ui.rect(W / 2 - 150, H * 0.4f + 104, 300, 12, 0xc0000000);
            ui.rect(W / 2 - 150, H * 0.4f + 104, 300 * k, 12, 0xff5ad17a);
        }
        ui.text("WASD hareket  •  sol tık basılı: fareye nişan  •  Q E R F yetenek  •  ESC " + (role == Role.SOLO ? "duraklat" : "menü"), 14, H - 24, 13, 0x90ffffff);
    }

    private void renderLevelUp() {
        float W = winW, H = winH;
        ui.rect(0, 0, W, H, 0xb005070d);
        ui.textC("SEVİYE ATLADINIZ!", W / 2, H * 0.17f, 52, 0xffffd84a);
        Player p = player;
        if (p.picked) {
            ui.textC("Seçimin alındı. Diğer oyuncular seçiyor...", W / 2, H * 0.17f + 66, 22, 0xffd0d6e0);
            float y = H * 0.36f;
            for (Player q : players) {
                ui.textC(q.displayName() + (q.picked || q.local ? "  • hazır" : "  • seçiyor..."), W / 2, y, 20, q.picked || q.local ? 0xff5ad17a : 0xffffb347);
                y += 30;
            }
            return;
        }
        ui.textC("Bir güçlendirme seç  (1 / 2 / 3 veya tıkla)", W / 2, H * 0.17f + 66, 20, 0xffd0d6e0);
        for (int i = 0; i < p.choices.size(); i++) {
            Upgrade u = p.choices.get(i);
            float[] rc = cardRect(i);
            boolean hov = inside(rc);
            ui.rect(rc[0], rc[1], rc[2], rc[3], hov ? 0xf62a3350 : 0xf01a2030);
            ui.rect(rc[0], rc[1], rc[2], 8, u.color);
            ui.frame(rc[0], rc[1], rc[2], rc[3], hov ? 3 : 1, hov ? u.color : 0xff40485c);
            ui.textS((i + 1) + "", rc[0] + 14, rc[1] + 18, 30, 0x90ffffff);
            ui.textS(u.name, rc[0] + 48, rc[1] + 22, 26, u.color);
            int cur = p.lvl(u.id);
            String lv = u.max > 999 ? "" : (cur == 0 ? "YENİ!" : "Seviye " + cur + " → " + (cur + 1));
            ui.text(lv, rc[0] + 48, rc[1] + 56, 16, cur == 0 ? 0xff7fe0ff : 0xffa0a8b8);
            float y = rc[1] + 92;
            for (String line : ui.wrap(u.desc, rc[2] - 40, 17)) { ui.text(line, rc[0] + 20, y, 17, 0xffe8ecf4); y += 24; }
        }
    }

    private void renderOverlay(String title, String sub, int color) {
        float W = winW, H = winH;
        ui.rect(0, 0, W, H, 0xa005070d);
        ui.textC(title, W / 2, H * 0.32f, 72, color);
        float y = H * 0.32f + 100;
        for (String line : sub.split("\n")) { ui.textC(line, W / 2, y, 22, 0xffe0e6f0); y += 36; }
    }
}
