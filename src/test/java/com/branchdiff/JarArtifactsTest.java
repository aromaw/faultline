package com.branchdiff;

import com.branchdiff.analysis.JarArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JarArtifactsTest {
    @Test
    void libraryClassesMapToTheArtifactOfTheirJar(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("repository/tools/jackson/core/jackson-databind/3.0.1"));
        Path jar = dir.resolve("jackson-databind-3.0.1.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("tools/jackson/databind/BeanProperty.class"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("tools/jackson/databind/Outer$Inner.class"));
            z.closeEntry();
        }
        Path odd = Files.createDirectories(tmp.resolve("lib")).resolve("random.jar");
        Files.copy(jar, odd);
        JarArtifacts a = new JarArtifacts(List.of(odd, jar));
        assertEquals("tools.jackson.core:jackson-databind", a.artifactOf("tools.jackson.databind.BeanProperty").ga());
        assertEquals("3.0.1", a.artifactOf("tools.jackson.databind.Outer.Inner").version());
        assertNull(a.artifactOf("org.example.Missing"));
        assertEquals("3.0.1", a.get("tools.jackson.core:jackson-databind").version());
    }
}
