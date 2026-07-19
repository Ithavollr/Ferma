package org.evlis.firma.utils.chunk.fixup;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiomeValidatorTest {

    private ServerMock server;
    private BiomeValidator validator;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        validator = new BiomeValidator();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testValidVanillaBiome() {
        assertTrue(validator.isValid("minecraft:plains"),
                "minecraft:plains should be valid in the biome registry");
    }

    @Test
    void testValidNetherBiome() {
        assertTrue(validator.isValid("minecraft:nether_wastes"),
                "minecraft:nether_wastes should be valid in the biome registry");
    }

    @Test
    void testValidEndBiome() {
        assertTrue(validator.isValid("minecraft:the_end"),
                "minecraft:the_end should be valid in the biome registry");
    }

    @Test
    void testInvalidCustomBiome() {
        assertFalse(validator.isValid("terra:void_ending"),
                "terra:void_ending should be invalid (not in registry)");
    }

    @Test
    void testInvalidFermaBiome() {
        assertFalse(validator.isValid("ferma:custom_biome"),
                "ferma:custom_biome should be invalid (not in registry)");
    }

    @Test
    void testInvalidMalformedKey() {
        assertFalse(validator.isValid("not_a_valid_key"),
                "Malformed key without namespace should be invalid");
    }

    @Test
    void testNullKey() {
        assertFalse(validator.isValid(null),
                "Null key should be invalid");
    }

    @Test
    void testEmptyKey() {
        assertFalse(validator.isValid(""),
                "Empty key should be invalid");
    }
}
