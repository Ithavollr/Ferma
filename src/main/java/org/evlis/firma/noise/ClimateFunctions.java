package org.evlis.firma.noise;

/**
 * Helper functions for climate noise parameters.
 */
public class ClimateFunctions {

    /**
     * Convert raw weirdness noise into the "ridges" (peaks/valleys) curve.
     * This is the standard ridge noise fold used by vanilla.
     */
    public static double weirdnessToRidges(double weirdness) {
        return -3.0 * (Math.abs(weirdness) - 2.0/3.0);
    }

    /**
     * Y-clamped gradient for the depth parameter.
     * Creates a linear gradient over Y that affects biome depth.
     */
    public static double yClampedGradient(double y, double minY, double maxY) {
        // Normalize Y to [0, 1] range
        double normalized = (y - minY) / (maxY - minY);
        // Invert and clamp to [-1, 1]
        return Math.max(-1.0, Math.min(1.0, 1.0 - normalized * 2.0));
    }

    /**
     * Alternative depth calculation that includes terrain offset.
     * This matches vanilla's depth parameter more closely.
     */
    public static double depthWithTerrainOffset(double y, double minY, double maxY, double terrainOffset) {
        double gradient = yClampedGradient(y, minY, maxY);
        return Math.max(-1.0, Math.min(1.0, gradient + terrainOffset));
    }

    /**
     * Clamp value to [-1, 1] range, as expected by biome sampling.
     */
    public static double clampClimate(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
