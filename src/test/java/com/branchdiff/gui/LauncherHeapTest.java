package com.branchdiff.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The launcher's default heap: a quarter of the memory, at least 1g, at most 8g; FAULTLINE_XMX wins. */
class LauncherHeapTest {
    private static String opts(Map<String, String> env) throws IOException, InterruptedException {
        Path script = Path.of("scripts/faultline");
        Assumptions.assumeTrue(Files.exists(script) && Files.exists(Path.of("/bin/sh")));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", script.toString());
        pb.environment().put("FAULTLINE_JAR", "pom.xml");
        pb.environment().put("FAULTLINE_PRINT_OPTS", "1");
        pb.environment().remove("FAULTLINE_XMX");
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    private static String xmx(Map<String, String> env) throws IOException, InterruptedException {
        for (String o : opts(env).split(" ")) if (o.startsWith("-Xmx")) return o.substring(4);
        throw new AssertionError("no -Xmx");
    }

    @Test
    void quarterOfMemoryWithFloorAndCap() throws Exception {
        assertEquals("1024m", xmx(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(2L << 30))));
        assertEquals("4096m", xmx(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(16L << 30))));
        assertEquals("8192m", xmx(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(64L << 30))));
        assertEquals("1g", xmx(Map.of("FAULTLINE_MEM_BYTES", "unknown")));
        assertEquals("3g", xmx(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(16L << 30), "FAULTLINE_XMX", "3g")));
    }

    @Test
    void gcFlagsDefaultToTunedG1AndCanBeReplaced() throws Exception {
        String def = opts(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(8L << 30)));
        assertTrue(def.startsWith("-XX:+UseG1GC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:GCTimeRatio=4 -Xmx2048m"), def);
        assertTrue(def.contains("-XX:CICompilerCount=2"), def);
        String serial = opts(Map.of("FAULTLINE_MEM_BYTES", String.valueOf(8L << 30), "FAULTLINE_GC_OPTS", "-XX:+UseSerialGC"));
        assertTrue(serial.startsWith("-XX:+UseSerialGC -Xmx2048m"), serial);
        assertFalse(serial.contains("G1"), serial);
    }

    @Test
    void dockOptionsOnlyOnMac() throws Exception {
        String mac = opts(Map.of("FAULTLINE_OS", "Darwin", "FAULTLINE_MEM_BYTES", String.valueOf(8L << 30)));
        assertTrue(mac.contains("-Xdock:name=faultline"), mac);
        assertTrue(mac.contains("-Xdock:icon=") && mac.endsWith("faultline.icns"), mac);
        assertTrue(mac.contains("-Dapple.awt.application.name=faultline"), mac);
        String linux = opts(Map.of("FAULTLINE_OS", "Linux", "FAULTLINE_MEM_BYTES", String.valueOf(8L << 30)));
        assertFalse(linux.contains("-Xdock"), linux);
    }
}
