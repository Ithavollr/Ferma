package org.evlis.firma.noise;

/**
 * 3D Improved Perlin noise sampler.
 * This is the foundation of Minecraft's noise system.
 * Must be bit-exact to vanilla for deterministic terrain generation.
 */
public class PerlinNoiseSampler {
    private static final float GRADIENT_DOT_SCALE = 1.0F / 1032.0F;
    
    // 16 standard gradient vectors for 3D Perlin noise
    private static final int[][] GRADIENTS = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
        {1, 1, 1}, {-1, 1, 1}, {1, -1, 1}, {-1, -1, 1}
    };

    private final byte[] permutations;
    public final double originX;
    public final double originY;
    public final double originZ;

    public PerlinNoiseSampler(XoroshiroRandomSource random) {
        this.originX = random.nextDouble() * 256.0;
        this.originY = random.nextDouble() * 256.0;
        this.originZ = random.nextDouble() * 256.0;
        this.permutations = new byte[256];

        // Initialize permutation table
        for (int i = 0; i < 256; i++) {
            this.permutations[i] = (byte)i;
        }

        // Shuffle using Fisher-Yates
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i);
            byte b = this.permutations[i];
            this.permutations[i] = this.permutations[i + j];
            this.permutations[i + j] = b;
        }
    }

    /**
     * Sample noise at the given coordinates.
     */
    public double sample(double x, double y, double z) {
        return this.sample(x, y, z, 0.0, 0.0);
    }

    /**
     * Sample noise with optional Y scaling for terrain generation.
     */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.originX;
        double e = y + this.originY;
        double f = z + this.originZ;
        int i = (int)Math.floor(d);
        int j = (int)Math.floor(e);
        int k = (int)Math.floor(f);
        double g = d - (double)i;
        double h = e - (double)j;
        double l = f - (double)k;
        double n;
        if (yScale != 0.0) {
            double m;
            if (yMax >= 0.0 && yMax < h) {
                m = yMax;
            } else {
                m = h;
            }
            n = (double)Math.floor(m / yScale + 1.0E-7F) * yScale;
        } else {
            n = 0.0;
        }
        return this.sample(i, j, k, g, h - n, l, h);
    }

    /**
     * Internal sampling method with pre-computed integer and fractional parts.
     */
    private double sample(int sectionX, int sectionY, int sectionZ, 
                         double localX, double localY, double localZ, double fadeLocalX) {
        int i = this.getGradient(sectionX);
        int j = this.getGradient(sectionX + 1);
        int k = this.getGradient(i + sectionY);
        int l = this.getGradient(i + sectionY + 1);
        int m = this.getGradient(j + sectionY);
        int n = this.getGradient(j + sectionY + 1);
        
        // Sample at 8 corners of the unit cube
        double d = grad(this.getGradient(k + sectionZ), localX, localY, localZ);
        double e = grad(this.getGradient(m + sectionZ), localX - 1.0, localY, localZ);
        double f = grad(this.getGradient(l + sectionZ), localX, localY - 1.0, localZ);
        double g = grad(this.getGradient(n + sectionZ), localX - 1.0, localY - 1.0, localZ);
        double h = grad(this.getGradient(k + sectionZ + 1), localX, localY, localZ - 1.0);
        double o = grad(this.getGradient(m + sectionZ + 1), localX - 1.0, localY, localZ - 1.0);
        double p = grad(this.getGradient(l + sectionZ + 1), localX, localY - 1.0, localZ - 1.0);
        double q = grad(this.getGradient(n + sectionZ + 1), localX - 1.0, localY - 1.0, localZ - 1.0);
        
        // Apply fade curves
        double r = perlinFade(localX);
        double s = perlinFade(fadeLocalX);
        double t = perlinFade(localZ);
        
        // Trilinear interpolation
        return lerp3(r, s, t, d, e, f, g, h, o, p, q);
    }

    /**
     * Compute dot product of gradient vector with position vector.
     */
    private static double grad(int hash, double x, double y, double z) {
        int[] gradient = GRADIENTS[hash & 15];
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }

    /**
     * Get gradient index from permutation table.
     */
    private int getGradient(int hash) {
        return this.permutations[hash & 0xFF] & 0xFF;
    }

    /**
     * Quintic fade function: 6t^5 - 15t^4 + 10t^3
     */
    private static double perlinFade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    /**
     * Trilinear interpolation of 8 values.
     */
    private static double lerp3(double fadeX, double fadeY, double fadeZ,
                               double d000, double d100, double d010, double d110,
                               double d001, double d101, double d011, double d111) {
        double d00 = lerp(fadeX, d000, d100);
        double d01 = lerp(fadeX, d010, d110);
        double d10 = lerp(fadeX, d001, d101);
        double d11 = lerp(fadeX, d011, d111);
        double d0 = lerp(fadeY, d00, d01);
        double d1 = lerp(fadeY, d10, d11);
        return lerp(fadeZ, d0, d1);
    }

    /**
     * Linear interpolation between two values.
     */
    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
