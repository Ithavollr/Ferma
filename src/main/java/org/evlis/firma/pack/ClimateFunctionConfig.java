package org.evlis.firma.pack;

import java.util.List;

/**
 * Sealed interface for climate function configurations.
 * Each implementation represents a different type of climate function
 * that can be used in a pack.
 */
public sealed interface ClimateFunctionConfig {
    
    String type();
    
    /**
     * Constant value function - returns a fixed value.
     */
    record Constant(double value) implements ClimateFunctionConfig {
        @Override
        public String type() { return "constant"; }
    }
    
    /**
     * Identity function - passes through vanilla.
     */
    record Identity() implements ClimateFunctionConfig {
        @Override
        public String type() { return "identity"; }
    }
    
    /**
     * Perlin noise (single octave).
     */
    record Perlin(double xzScale, double yScale) implements ClimateFunctionConfig {
        @Override
        public String type() { return "perlin"; }
    }
    
    /**
     * Octave Perlin noise (fractal composition).
     */
    record OctavePerlin(int firstOctave, List<Double> amplitudes, double xzScale, double yScale) 
            implements ClimateFunctionConfig {
        @Override
        public String type() { return "octave_perlin"; }
    }
    
    /**
     * Double Perlin noise (two octaves with offset for grid artifact masking).
     */
    record DoublePerlin(int firstOctave, List<Double> amplitudes, double xzScale, double yScale) 
            implements ClimateFunctionConfig {
        @Override
        public String type() { return "double_perlin"; }
    }
    
    /**
     * Shifted noise - wraps another noise with XZ position shifts.
     */
    record ShiftedNoise(ClimateFunctionConfig inner, ShiftConfig shiftX, ShiftConfig shiftZ) 
            implements ClimateFunctionConfig {
        @Override
        public String type() { return "shifted_noise"; }
        
        public record ShiftConfig(String type, int firstOctave, List<Double> amplitudes) {}
    }
    
    /**
     * Weirdness to ridges fold function.
     */
    record WeirdnessToRidges(ClimateFunctionConfig source) implements ClimateFunctionConfig {
        @Override
        public String type() { return "weirdness_to_ridges"; }
    }

    /**
     * Radial gradient — returns {@code startValue} at the center and changes by
     * {@code rate} per block of XZ distance (signed). Result is clamped to
     * {@code [clampMin, clampMax]}.
     *
     * <p>Expresses falloff, fall-up, plateau-then-drop, and bullseye bands
     * via the same five parameters. See {@code RadialGradientClimateFunction}
     * for detailed usage examples (high_mountain, frozen core, etc.).
     */
    record RadialGradient(
        double centerX,
        double centerZ,
        double startValue,
        double rate,
        double clampMin,
        double clampMax
    ) implements ClimateFunctionConfig {
        @Override
        public String type() { return "radial_gradient"; }
    }
}
