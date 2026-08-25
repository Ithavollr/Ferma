package org.evlis.firma.noise;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For every Ferma {@link DensityFunction} we ever inject into {@code RandomState},
 * this test instantiates a real instance and asserts its declared {@code codec()}
 * errors on encode. If anyone replaces {@code UnserializableMapCodec.of(...)} with
 * {@code MapCodec.unit(...)} (the historical corruption vector), this test fails.
 *
 * <p>No mocking - every class under test is a real Ferma class operating on real
 * Mojang DataFixerUpper APIs.
 */
class FermaDensityFunctionCodecGuardTest {

    /**
     * NMS classes touch {@code BuiltInRegistries} during their static init, which
     * requires {@code Bootstrap.bootStrap()} to have been called. Without this,
     * any {@code DensityFunction} reference fails with "Not bootstrapped".
     */
    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void constant_codecErrorsOnEncode() {
        assertCodecErrorsOnEncode(new FermaClimateFunction.Constant(0.5));
    }

    @Test
    void identity_codecErrorsOnEncode() {
        // Wrap a trivial vanilla DensityFunction; identity inherits the same codec.
        assertCodecErrorsOnEncode(new FermaClimateFunction.Identity(
            new FermaClimateFunction.Constant(0.0)));
    }

    @Test
    void weirdnessToRidges_codecErrorsOnEncode() {
        assertCodecErrorsOnEncode(new FermaClimateFunction.WeirdnessToRidges(
            new FermaClimateFunction.Constant(0.0)));
    }

    @Test
    void doublePerlinClimate_codecErrorsOnEncode() {
        DoublePerlinClimateFunction df = new DoublePerlinClimateFunction(
            new PositionalRandomFactory(0L),
            "test",
            -7,
            new double[]{1.0},
            0.25,
            0.0,
            -1.0,
            1.0
        );
        assertCodecErrorsOnEncode(df);
    }

    @Test
    void shatteredClimate_codecErrorsOnEncode() {
        ShatteredClimateFunction df = new ShatteredClimateFunction(
            new PositionalRandomFactory(0L),
            -9,
            9,
            0.25,
            0.0,
            -1.0,
            1.0
        );
        assertCodecErrorsOnEncode(df);
    }

    @Test
    void yGradientClimate_codecErrorsOnEncode() {
        assertCodecErrorsOnEncode(new YGradientClimateFunction(-64, 320, 1.5, -1.5));
    }

    @Test
    void radialGradient_codecErrorsOnEncode() {
        // Bidirectional radial gradient: start 1.0 at center, fall -0.0002 per block, clamp at -1.05
        RadialGradientClimateFunction df = new RadialGradientClimateFunction(
            0.0, 0.0, 1.0, -0.0002, -1.05, 1.0
        );
        assertCodecErrorsOnEncode(df);
    }

    /**
     * Drives the actual encode check: get the {@link MapCodec} from the function's
     * {@code KeyDispatchDataCodec}, convert to a regular {@link Codec}, and assert
     * that {@code encodeStart} produces an error.
     */
    private static void assertCodecErrorsOnEncode(DensityFunction df) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        MapCodec<DensityFunction> mapCodec =
            (MapCodec<DensityFunction>) (MapCodec) df.codec().codec();
        Codec<DensityFunction> codec = mapCodec.codec();

        DataResult<JsonElement> result = codec.encodeStart(JsonOps.INSTANCE, df);

        assertTrue(result.isError(),
            df.getClass().getSimpleName() + ".codec() must error on encode " +
            "to prevent silent level.dat corruption, but encoded successfully as: "
                + result.result().orElse(null));
    }
}
