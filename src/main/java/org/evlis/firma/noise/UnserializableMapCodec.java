package org.evlis.firma.noise;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import java.util.stream.Stream;

/**
 * A {@link MapCodec} that <strong>refuses to encode</strong>.
 *
 * <p>Every Ferma {@link net.minecraft.world.level.levelgen.DensityFunction} we inject into
 * {@code RandomState} uses this codec. The contract is: these objects must never appear in
 * a serialization path. They are reflected into transient {@code RandomState} fields only;
 * {@code NoiseBasedChunkGenerator.settings} must remain the original registry-keyed Holder.
 *
 * <p>If a future regression breaks that invariant (e.g. someone replaces {@code settings}
 * with {@code Holder.direct(...)}), Mojang's codec system will eventually try to encode our
 * function. Without this guard, {@code MapCodec.unit(this)} would silently encode as
 * {@code {}}, Mojang's {@code resultOrPartial(...)} salvage path would write the empty
 * object into {@code level.dat}, and the next world load would fail with the infamous
 * {@code "No key dimensions in MapLike[{}]"} error.
 *
 * <p>With this codec, encoding instead returns a {@link DataResult.Error} carrying a clear
 * message identifying the offending Ferma class. The error propagates up through Mojang's
 * record builders; the save aborts loudly with a stack trace pointing at the bug, and
 * {@code level.dat} is left intact.
 *
 * <p>Decoding is permissive (returns a supplied default) so the JVM can recover gracefully
 * from any historical corruption that predates this guard.
 */
public final class UnserializableMapCodec {

    private UnserializableMapCodec() {}

    /**
     * Build a {@link MapCodec} that errors on encode and returns {@code recoveryDefault}
     * on decode.
     *
     * @param identifier human-readable name of the offending class, included in the error.
     * @param recoveryDefault value to return if anyone ever decodes this codec; should be
     *                       a safe sentinel (e.g. a zero {@code Constant}).
     */
    public static <A> MapCodec<A> of(String identifier, A recoveryDefault) {
        final String errorMessage =
            "Ferma DensityFunction '" + identifier + "' is not serializable and must never " +
            "appear in a codec encode path. This indicates a regression in NMSInjectListener: " +
            "RandomState patches must remain transient and never leak into " +
            "NoiseBasedChunkGenerator.settings or any other serialized field. " +
            "level.dat write aborted to prevent corruption.";

        return new MapCodec<A>() {
            @Override
            public <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return prefix.withErrorsFrom(DataResult.error(() -> errorMessage));
            }

            @Override
            public <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input) {
                return DataResult.success(recoveryDefault);
            }

            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.empty();
            }

            @Override
            public String toString() {
                return "UnserializableMapCodec[" + identifier + "]";
            }
        };
    }
}
