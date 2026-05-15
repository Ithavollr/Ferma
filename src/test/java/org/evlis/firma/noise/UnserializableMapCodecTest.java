package org.evlis.firma.noise;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link UnserializableMapCodec} fails encode and recovers on decode.
 *
 * <p>This codec is the cornerstone of our defense against silent {@code level.dat}
 * corruption. If a future change ever makes encode succeed, this test breaks.
 */
class UnserializableMapCodecTest {

    @Test
    void encode_returnsErrorWithIdentifier() {
        MapCodec<String> mc = UnserializableMapCodec.of("TestFunc", "fallback");
        Codec<String> codec = mc.codec();

        DataResult<JsonElement> result = codec.encodeStart(JsonOps.INSTANCE, "anything");

        assertTrue(result.isError(),
            "encode must error to prevent silent level.dat corruption, but produced: "
                + result.result());
        String message = result.error().orElseThrow().message();
        assertTrue(message.contains("TestFunc"),
            "error message must identify the offending class, was: " + message);
        assertTrue(message.contains("level.dat"),
            "error message must mention level.dat to make the corruption risk obvious, was: "
                + message);
    }

    @Test
    void decode_returnsRecoveryDefault() {
        MapCodec<String> mc = UnserializableMapCodec.of("TestFunc", "RECOVERY");
        Codec<String> codec = mc.codec();

        // Decode an empty object - this is what historical corruption would produce.
        JsonElement input = JsonOps.INSTANCE.emptyMap();
        DataResult<com.mojang.datafixers.util.Pair<String, JsonElement>> result =
            codec.decode(JsonOps.INSTANCE, input);

        assertTrue(result.result().isPresent(),
            "decode must succeed to allow recovery from historical corruption");
        assertEquals("RECOVERY", result.result().orElseThrow().getFirst());
    }

    @Test
    void encode_failsForEveryInput() {
        MapCodec<Integer> mc = UnserializableMapCodec.of("AlwaysFails", -1);
        Codec<Integer> codec = mc.codec();

        for (int v : new int[] {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            DataResult<JsonElement> result = codec.encodeStart(JsonOps.INSTANCE, v);
            assertTrue(result.isError(),
                "encode must error for every input; succeeded for " + v);
        }
    }
}
