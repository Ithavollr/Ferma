package org.evlis.firma.pack;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the packaging invariant that {@code PackLoader.extractDefaultPacks} relies on:
 * the build-generated {@code /packs/index.txt} exists and every id it names resolves to a
 * real {@code pack.yml} resource. A shipped pack that never reaches the index — or an
 * index entry whose pack was deleted — fails here rather than showing up as a runtime
 * warning, or worse, as a pack that silently never reaches users.
 */
class PackIndexTest {

    @Test
    void packIndex_existsAndEveryEntryResolves() {
        List<String> ids = readIndex();

        assertFalse(ids.isEmpty(), "pack index must not be empty");
        for (String id : ids) {
            String resource = "/packs/" + id + "/pack.yml";
            try (InputStream is = getClass().getResourceAsStream(resource)) {
                assertNotNull(is, "index names '" + id + "' but " + resource + " is not on the classpath");
            } catch (Exception e) {
                fail("failed reading " + resource + ": " + e);
            }
        }
    }

    @Test
    void packIndex_coversKnownPacks() {
        // Spot-check that packs referenced elsewhere (docs, tests, plan) are actually
        // shipped; catches a pack being dropped from resources without notice.
        List<String> ids = readIndex();
        assertTrue(ids.contains("vanilla_noise"), "vanilla_noise must ship; index was " + ids);
        assertTrue(ids.contains("layered_nether"), "layered_nether must ship; index was " + ids);
    }

    private List<String> readIndex() {
        try (InputStream is = getClass().getResourceAsStream("/packs/index.txt")) {
            assertNotNull(is, "/packs/index.txt missing - the generatePackIndex Gradle task did not run");
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (Exception e) {
            fail("failed reading pack index: " + e);
            return List.of();
        }
    }
}
