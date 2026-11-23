package swarm;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Giriş noktası. macOS'ta GLFW ana thread'de çalışmak zorunda (-XstartOnFirstThread).
 * IntelliJ'de ekstra ayar yapmadan "Run" diyebilmen için gerekirse JVM'i doğru
 * bayraklarla kendini yeniden başlatır.
 */
public final class Main {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true"); // font atlası için AWT, GLFW ile çakışmasın
        if (relaunchIfNeeded()) return;
        new Game().run();
    }

    private static boolean relaunchIfNeeded() {
        if ("true".equals(System.getProperty("swarm.child"))) return false;
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        int feature = Runtime.version().feature();

        boolean needFirstThread = mac && !jvmArgs.contains("-XstartOnFirstThread");
        boolean needUnsafe = feature >= 23 && jvmArgs.stream().noneMatch(a -> a.startsWith("--sun-misc-unsafe-memory-access"));
        if (!needFirstThread && !needUnsafe) return false;

        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (mac) cmd.add("-XstartOnFirstThread");
        if (feature >= 22) cmd.add("--enable-native-access=ALL-UNNAMED");
        if (feature >= 23) cmd.add("--sun-misc-unsafe-memory-access=allow");
        cmd.add("-Dswarm.child=true");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        try {
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            System.exit(p.waitFor());
        } catch (Exception e) {
            System.err.println("Yeniden başlatılamadı (" + e + "). Run Configuration > VM options kısmına "
                    + "-XstartOnFirstThread ekleyip tekrar dene.");
            return false;
        }
        return true;
    }
}
