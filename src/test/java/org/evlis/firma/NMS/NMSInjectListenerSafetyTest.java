package org.evlis.firma.NMS;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Source-level guard: scans {@code NMSInjectListener.java} for code patterns
 * known to corrupt {@code level.dat}.
 *
 * <p>This is a deliberately blunt instrument. Every match listed here was at some
 * point a real bug or a near-miss. If a future change legitimately needs to do
 * one of these things, update this test with reasoning - don't just delete it.
 */
class NMSInjectListenerSafetyTest {

    private static final Path SOURCE = Path.of(
        "src/main/java/org/evlis/firma/NMS/NMSInjectListener.java");

    /**
     * Forbidden: reflectively grabbing the {@code settings} field of
     * {@code NoiseBasedChunkGenerator}. Any write to that field round-trips through
     * the codec on save and corrupts {@code level.dat}.
     */
    @Test
    void doesNotReflectIntoNoiseBasedChunkGeneratorSettings() throws IOException {
        String src = readSource();
        Pattern pattern = Pattern.compile(
            "NoiseBasedChunkGenerator\\.class\\.getDeclaredField\\(\\s*\"settings\"\\s*\\)");
        assertFalse(pattern.matcher(src).find(),
            "NMSInjectListener must never reflect into NoiseBasedChunkGenerator.settings - " +
            "writing to that field corrupts level.dat via codec round-trip on save. " +
            "All noise patches must target transient RandomState fields only.");
    }

    /**
     * Forbidden: wrapping a {@code NoiseGeneratorSettings} in {@code Holder.direct(...)}.
     * That forces inline serialization of the entire settings record, including any
     * Ferma DensityFunctions, which silently encodes to {@code {}} and corrupts
     * {@code level.dat}.
     */
    @Test
    void doesNotConstructDirectSettingsHolder() throws IOException {
        String src = readSource();
        // Allow Holder.direct in unrelated contexts, but flag any pairing with NoiseGeneratorSettings.
        if (Pattern.compile("Holder\\.direct\\s*\\(").matcher(src).find()
            && src.contains("NoiseGeneratorSettings")) {
            fail("NMSInjectListener uses Holder.direct(...) alongside NoiseGeneratorSettings. " +
                "This combination historically corrupted level.dat. Verify this is safe and " +
                "update this test with rationale, or remove the call.");
        }
    }

    /**
     * Forbidden: assigning to a field literally named {@code settings} on the
     * NMS chunk generator. Catches non-reflective mutations the other guards miss.
     */
    @Test
    void doesNotAssignToNoiseGeneratorSettingsField() throws IOException {
        String src = readSource();
        // Match patterns like `noiseGenerator.settings =`, `generator.settings =`, etc.
        Pattern pattern = Pattern.compile(
            "\\b(noiseGenerator|generator|gen)\\s*\\.\\s*settings\\s*=");
        if (pattern.matcher(src).find()) {
            fail("NMSInjectListener appears to assign directly to a chunk generator's " +
                "'settings' field. This corrupts level.dat via codec round-trip on save.");
        }
    }

    /**
     * Sanity: confirm we *do* still patch {@code RandomState.router} - if this test
     * starts passing inverted (i.e. router patching disappears), our pack mode is
     * broken even if no corruption occurs.
     */
    @Test
    void stillPatchesRandomStateRouter() throws IOException {
        String src = readSource();
        Pattern pattern = Pattern.compile(
            "RandomState\\.class\\.getDeclaredField\\(\\s*\"router\"\\s*\\)");
        if (!pattern.matcher(src).find()) {
            fail("NMSInjectListener no longer patches RandomState.router - NOISE mode " +
                "cannot work without this. If this is intentional, update or remove " +
                "this test with reasoning.");
        }
    }

    private static String readSource() throws IOException {
        if (!Files.exists(SOURCE)) {
            fail("Cannot find " + SOURCE.toAbsolutePath() +
                " - is the working directory the project root?");
        }
        return Files.readString(SOURCE);
    }
}
