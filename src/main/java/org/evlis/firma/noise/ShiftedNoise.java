package org.evlis.firma.noise;

/**
 * Shifted noise wrapper - used by temperature and humidity in 1.18+.
 * Applies XZ position shifts before sampling the base noise.
 */
public class ShiftedNoise {
    private final DoublePerlinNoiseSampler baseNoise;
    private final DoublePerlinNoiseSampler shiftXNoise;
    private final DoublePerlinNoiseSampler shiftZNoise;
    private final double xzScale;
    private final double yScale;

    public ShiftedNoise(DoublePerlinNoiseSampler baseNoise,
                       DoublePerlinNoiseSampler shiftXNoise,
                       DoublePerlinNoiseSampler shiftZNoise,
                       double xzScale, double yScale) {
        this.baseNoise = baseNoise;
        this.shiftXNoise = shiftXNoise;
        this.shiftZNoise = shiftZNoise;
        this.xzScale = xzScale;
        this.yScale = yScale;
    }

    /**
     * Sample shifted noise at the given coordinates.
     */
    public double sample(double x, double y, double z) {
        // Sample shift noises at the original position
        double shiftX = this.shiftXNoise.sample(x, y, z) * 4.0;
        double shiftZ = this.shiftZNoise.sample(x, y, z) * 4.0;
        
        // Apply shifts and sample base noise
        return this.baseNoise.sample(
            x * this.xzScale + shiftX,
            y * this.yScale,
            z * this.xzScale + shiftZ
        );
    }

    /**
     * Sample with Y scaling.
     */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double shiftX = this.shiftXNoise.sample(x, y, z) * 4.0;
        double shiftZ = this.shiftZNoise.sample(x, y, z) * 4.0;
        
        return this.baseNoise.sample(
            x * this.xzScale + shiftX,
            y * this.yScale,
            z * this.xzScale + shiftZ,
            yScale * this.yScale,
            yMax * this.yScale
        );
    }
}
