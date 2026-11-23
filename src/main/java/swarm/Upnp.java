package swarm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modemde otomatik UDP port açma (UPnP Internet Gateway Device).
 * Host olunca çalışır; başarılı olursa arkadaşın sadece host kodunu girmesi yeter.
 * Başarısız olursa oyun iki yönlü kod alışverişiyle (hole punching) devam eder.
 * Açılan port 2 saatlik kiralanır, oyun kapanınca/odadan çıkınca silinir.
 */
final class Upnp {
    enum St { WORKING, OK, FAILED }

    private static final int LEASE = 7200;
    /** Sadece test: SSDP keşfini atlayıp bu cihaz adresini kullan. */
    static String testLocation;

    volatile St st = St.WORKING;
    volatile String status = "Modemde port otomatik açılıyor (UPnP)...";
    volatile InetSocketAddress external;
    private volatile String controlUrl, service, localIp;
    private volatile int extPort = -1;
    private volatile boolean closed;
    private final int internalPort;

    Upnp(int internalPort) { this.internalPort = internalPort; }

    void startAsync() {
        Thread t = new Thread(this::run, "upnp");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try {
            List<String> locs = testLocation != null ? List.of(testLocation) : discover();
            if (locs.isEmpty()) { fail("Modem UPnP'ye cevap vermedi (modem ayarlarında UPnP kapalı olabilir)."); return; }
            for (String loc : locs) {
                URL u = URI.create(loc).toURL();
                String xml = http(u, null, null);
                if (xml != null && findService(u, xml)) {
                    localIp = localIpToward(u.getHost());
                    break;
                }
            }
            if (controlUrl == null) { fail("Modem otomatik port açmayı desteklemiyor."); return; }
            String extIp = externalIp();
            for (int p = internalPort; p < internalPort + 6 && extPort < 0; p++) {
                if (add(p, LEASE) || add(p, 0)) extPort = p;
            }
            if (extPort < 0) { fail("Modem port açma isteğini reddetti."); return; }
            Runtime.getRuntime().addShutdownHook(new Thread(this::remove));
            if (extIp == null) { remove(); fail("Modem dış IP adresini vermedi."); return; }
            InetAddress ea = InetAddress.getByName(extIp);
            if (!(ea instanceof Inet4Address) || isPrivate(ea)) {
                remove();
                fail("Modemin kendisi de operatörün ağının arkasında (CGNAT, dış IP " + extIp + "). Otomatik açma işe yaramaz; kod alışverişiyle deneyin.");
                return;
            }
            external = new InetSocketAddress(ea, extPort);
            st = St.OK;
            status = "Modemde UDP " + extPort + " portu otomatik açıldı. Arkadaşının sadece senin kodunu girmesi yeter.";
            // kira süresi dolmasın diye yenile
            while (!closed) {
                Thread.sleep(LEASE * 1000L / 2);
                if (!closed) add(extPort, LEASE);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            fail("Otomatik port açılamadı (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void fail(String s) {
        st = St.FAILED;
        status = s;
    }

    /** Oyun odadan çıkınca: port eşlemesini sil (en fazla ~1.5 sn bekler). */
    void close() {
        closed = true;
        Thread t = new Thread(this::remove, "upnp-remove");
        t.setDaemon(true);
        t.start();
        try { t.join(1500); } catch (InterruptedException ignored) { }
    }

    private synchronized void remove() {
        if (extPort < 0 || controlUrl == null) return;
        int p = extPort;
        extPort = -1;
        try {
            soap("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + p + "</NewExternalPort><NewProtocol>UDP</NewProtocol>");
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ SSDP keşfi
    private static List<String> discover() throws IOException {
        List<String> found = new ArrayList<>();
        String[] types = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:1"};
        InetSocketAddress mc = new InetSocketAddress("239.255.255.250", 1900);
        Pattern locP = Pattern.compile("(?im)^location:\\s*(\\S+)");
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(500);
            long end = System.currentTimeMillis() + 3000;
            int round = 0;
            while (System.currentTimeMillis() < end) {
                if (round++ < 3) {
                    for (String t : types) {
                        byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                                + t + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                        s.send(new DatagramPacket(m, m.length, mc));
                    }
                }
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    Matcher mm = locP.matcher(new String(buf, 0, p.getLength(), StandardCharsets.ISO_8859_1));
                    if (mm.find()) {
                        String loc = mm.group(1);
                        if (loc.startsWith("http://") && !found.contains(loc)) found.add(loc);
                        if (found.size() >= 4) break;
                    }
                } catch (SocketTimeoutException ignored) {
                    if (!found.isEmpty() && round > 2) break;
                }
            }
        }
        return found;
    }

    private boolean findService(URL loc, String xml) {
        String base = tag(xml, "URLBase");
        Matcher m = Pattern.compile("(?s)<service>(.*?)</service>").matcher(xml);
        String bestType = null, bestCtrl = null;
        while (m.find()) {
            String sv = m.group(1);
            String type = tag(sv, "serviceType"), ctrl = tag(sv, "controlURL");
            if (type == null || ctrl == null) continue;
            if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                if (bestType == null || type.contains("WANIPConnection")) { bestType = type; bestCtrl = ctrl; }
            }
        }
        if (bestType == null) return false;
        try {
            URI b = URI.create(base != null && !base.isBlank() ? base.trim() : loc.toString());
            controlUrl = b.resolve(bestCtrl.trim()).toString();
            service = bestType.trim();
            return controlUrl.startsWith("http://");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("(?s)<(?:\\w+:)?" + name + ">(.*?)</(?:\\w+:)?" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String localIpToward(String host) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(host), 1900);
            return s.getLocalAddress().getHostAddress();
        }
    }

    static boolean isPrivate(InetAddress a) {
        byte[] b = a.getAddress();
        int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
        return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || (b0 == 100 && b1 >= 64 && b1 < 128); // 100.64.0.0/10 (operatör CGNAT)
    }

    // ------------------------------------------------------------------ SOAP
    private String externalIp() throws IOException {
        String r = soap("GetExternalIPAddress", "");
        return r == null ? null : tag(r, "NewExternalIPAddress");
    }

    private boolean add(int ext, int lease) {
        if (localIp == null) return false;
        try {
            return soap("AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + ext + "</NewExternalPort>"
                    + "<NewProtocol>UDP</NewProtocol><NewInternalPort>" + internalPort + "</NewInternalPort>"
                    + "<NewInternalClient>" + localIp + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                    + "<NewPortMappingDescription>Rift Surusu</NewPortMappingDescription>"
                    + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private String soap(String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        return http(URI.create(controlUrl).toURL(), body, service + "#" + action);
    }

    /** Basit HTTP; hata kodunda null döner. Modem yerel ağda olduğu için proxy kullanılmaz. */
    private static String http(URL url, String body, String soapAction) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2500);
        c.setReadTimeout(3000);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code >= 400) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 512 * 1024) bo.write(buf, 0, n);
                return bo.toString(StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
