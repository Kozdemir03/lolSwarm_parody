package swarm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * UDP soket + STUN (genel IP:port keşfi) + oda kodu.
 * Paketler arka plan thread'inde alınır, ana thread {@link #inbox}'tan okur.
 */
final class Net {
    record Packet(InetSocketAddress from, byte[] data) { }

    static final int DEFAULT_PORT = 27015;
    private static final String[][] STUN = {
            {"stun.l.google.com", "19302"}, {"stun1.l.google.com", "19302"},
            {"stun.cloudflare.com", "3478"}, {"stun2.l.google.com", "19302"}};
    private static final int COOKIE = 0x2112A442;
    static final String ALPH = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32 (I, L, O, U yok)

    /** Test için yapay paket kaybı oranı (-Dswarm.netloss=0.1). */
    static final double LOSS = Double.parseDouble(System.getProperty("swarm.netloss", "0"));
    /** Test için yapay gecikme, ms (-Dswarm.netdelay=60). */
    static final int DELAY = Integer.getInteger("swarm.netdelay", 0);
    private static final java.util.concurrent.ScheduledExecutorService DELAYER = DELAY > 0
            ? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "net-delay"); t.setDaemon(true); return t; })
            : null;
    static final java.util.concurrent.atomic.AtomicLong BYTES_SENT = new java.util.concurrent.atomic.AtomicLong();
    final DatagramSocket sock;
    final ConcurrentLinkedQueue<Packet> inbox = new ConcurrentLinkedQueue<>();
    volatile InetSocketAddress publicAddr;
    volatile String stunStatus = "Genel adres bulunuyor (STUN)...";
    private final byte[] txn = new byte[12];
    private volatile boolean closed;

    Net(int preferredPort) throws SocketException {
        DatagramSocket s;
        try {
            s = new DatagramSocket(preferredPort);
        } catch (SocketException e) {
            s = new DatagramSocket(0); // port doluysa rastgele bir port
        }
        sock = s;
        new SecureRandom().nextBytes(txn);
        Thread t = new Thread(this::rxLoop, "net-rx");
        t.setDaemon(true);
        t.start();
    }

    int port() { return sock.getLocalPort(); }

    private void rxLoop() {
        byte[] buf = new byte[2048];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(p);
            } catch (IOException e) {
                // Windows: ulaşılamayan porta gönderim sonrası receive() "connection reset" fırlatabilir → devam
                if (closed || sock.isClosed()) return;
                continue;
            }
            byte[] d = Arrays.copyOf(p.getData(), p.getLength());
            if (isStun(d)) parseStun(d);
            else if (inbox.size() < 4096) inbox.add(new Packet((InetSocketAddress) p.getSocketAddress(), d));
        }
    }

    void send(InetSocketAddress to, byte[] data, int len) {
        if (closed || to == null) return;
        if (LOSS > 0 && Math.random() < LOSS) return;
        BYTES_SENT.addAndGet(len + 28);
        if (DELAYER != null) {
            byte[] copy = Arrays.copyOf(data, len);
            int jitter = (int) (Math.random() * DELAY * 0.3);
            DELAYER.schedule(() -> rawSend(to, copy, copy.length), DELAY + jitter, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        rawSend(to, data, len);
    }

    private void rawSend(InetSocketAddress to, byte[] data, int len) {
        if (closed) return;
        try {
            sock.send(new DatagramPacket(data, len, to));
        } catch (IOException ignored) {
        }
    }

    void close() {
        closed = true;
        sock.close();
    }

    // ------------------------------------------------------------------ STUN (RFC 5389)
    void stunAsync() {
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 4 && publicAddr == null && !closed; attempt++) {
                for (String[] s : STUN) {
                    try {
                        InetAddress a = resolveV4(s[0]);
                        if (a != null) {
                            byte[] req = stunRequest();
                            send(new InetSocketAddress(a, Integer.parseInt(s[1])), req, req.length);
                        }
                    } catch (Exception ignored) {
                    }
                }
                try { Thread.sleep(800); } catch (InterruptedException e) { return; }
            }
            if (publicAddr == null)
                stunStatus = "Genel adres bulunamadı (internet ya da UDP engelli olabilir). Doğrudan IP:port ile dene.";
        }, "stun");
        t.setDaemon(true);
        t.start();
    }

    private static InetAddress resolveV4(String host) throws IOException {
        for (InetAddress a : InetAddress.getAllByName(host)) if (a instanceof Inet4Address) return a;
        return null;
    }

    private byte[] stunRequest() {
        byte[] r = new byte[20];
        r[0] = 0x00; r[1] = 0x01; // Binding Request, uzunluk 0
        r[4] = 0x21; r[5] = 0x12; r[6] = (byte) 0xA4; r[7] = 0x42;
        System.arraycopy(txn, 0, r, 8, 12);
        return r;
    }

    private static boolean isStun(byte[] d) {
        return d.length >= 20 && d[0] == 0x01 && d[1] == 0x01
                && d[4] == 0x21 && d[5] == 0x12 && d[6] == (byte) 0xA4 && d[7] == 0x42;
    }

    private void parseStun(byte[] d) {
        int len = ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        int i = 20, end = Math.min(d.length, 20 + len);
        InetSocketAddress mapped = null;
        while (i + 4 <= end) {
            int type = ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
            int alen = ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
            int v = i + 4;
            if (v + alen > end) break;
            if ((type == 0x0020 || type == 0x0001) && alen >= 8 && d[v + 1] == 0x01) {
                int port = ((d[v + 2] & 0xff) << 8) | (d[v + 3] & 0xff);
                byte[] ip = Arrays.copyOfRange(d, v + 4, v + 8);
                if (type == 0x0020) {
                    port ^= COOKIE >>> 16;
                    for (int k = 0; k < 4; k++) ip[k] ^= (byte) (COOKIE >>> (24 - 8 * k));
                }
                try {
                    InetSocketAddress a = new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    if (type == 0x0020 || mapped == null) mapped = a;
                } catch (IOException ignored) {
                }
            }
            i = v + ((alen + 3) & ~3);
        }
        if (mapped != null && publicAddr == null) {
            publicAddr = mapped;
            stunStatus = "hazır";
        }
    }

    // ------------------------------------------------------------------ adresler / oda kodu
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    /** IPv4 + port (6 bayt) → "XXXX-XXXX-XX". */
    static String encode(InetSocketAddress a) {
        byte[] ip = a.getAddress().getAddress();
        if (ip.length != 4) return null;
        long v = 0;
        for (byte x : ip) v = (v << 8) | (x & 0xff);
        v = (v << 16) | (a.getPort() & 0xffff);
        char[] c = new char[10];
        for (int i = 9; i >= 0; i--) {
            c[i] = ALPH.charAt((int) (v & 31));
            v >>>= 5;
        }
        String s = new String(c);
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8);
    }

    /** Oda kodu veya "ip:port" / "alanadi:port" kabul eder. */
    static InetSocketAddress decode(String s) {
        s = s.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("boş");
        if (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) {
            String host = s;
            int port = DEFAULT_PORT;
            int c = s.lastIndexOf(':');
            if (c > 0) {
                host = s.substring(0, c);
                try { port = Integer.parseInt(s.substring(c + 1).trim()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("port hatalı"); }
            }
            try {
                return new InetSocketAddress(InetAddress.getByName(host.trim()), port);
            } catch (IOException e) {
                throw new IllegalArgumentException("adres bulunamadı");
            }
        }
        String t = s.toUpperCase().replaceAll("[\\s-]", "").replace('O', '0').replace('I', '1').replace('L', '1');
        if (t.length() != 10) throw new IllegalArgumentException("kod 10 karakter olmalı");
        long v = 0;
        for (int i = 0; i < 10; i++) {
            int k = ALPH.indexOf(t.charAt(i));
            if (k < 0) throw new IllegalArgumentException("geçersiz karakter: " + t.charAt(i));
            v = (v << 5) | k;
        }
        int port = (int) (v & 0xffff);
        v >>>= 16;
        byte[] ip = {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (IOException e) {
            throw new IllegalArgumentException("kod çözülemedi");
        }
    }
}
