package org.evlis.firma.preview;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.evlis.firma.Ferma;
import org.evlis.firma.NMS.GraphSurgeryDiagnostic;
import org.evlis.firma.NMS.NMSInjectListener;
import org.evlis.firma.pack.FermaPack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Seed-preview map tiles for the website: {@code GET /<type>/<pack>/<seed>/<z>/<x>/<y>.png}.
 *
 * <p>Renders what a book world would generate: the overworld type's own noise settings and
 * biome source, patched by the same {@link NMSInjectListener#patchRandomState} as world init.
 * There is no vanilla fallback: a pack Ferma cannot patch returns 500, never a vanilla map.
 * Biome color per pixel at the column's preliminary surface level, with stepped elevation
 * shading and contour lines baked in (mcseedmap.net's defaults). 256px tiles; zoom
 * {@value #MAX_ZOOM} is 4 blocks/pixel (biome resolution), each zoom out doubles that.
 */
public final class PreviewServer {
    private static final int TILE = 256;
    private static final int MAX_ZOOM = 8;
    private static final long WORLD_LIMIT = 30_000_000L;
    private static final int STEP = 8;             // stepped-shading band height, blocks
    private static final int CONTOUR = 32;         // contour interval, blocks
    private static final int CONTOUR_MAX_BPP = 16; // further out, contours turn into noise
    // Overworld types a book may use. Only one today; skylands etc. add an entry here.
    private static final Map<String, ResourceKey<LevelStem>> TYPES = Map.of("overworld", LevelStem.OVERWORLD);

    private record Preview(RandomState randomState, BiomeSource biomes, int minY, int maxY) {}

    private final Ferma plugin;
    private final HttpServer http;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, Integer> colors = new HashMap<>();
    // wozniak: LRU of 8 under one lock, so a cold build blocks other cold builds (warm ones render
    // unlocked). Ceiling: concurrent first requests for different seeds queue. Upgrade: per-key futures.
    private final Map<String, Preview> previews = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
            return size() > 8;
        }
    };

    /** @param bind host:port, e.g. 0.0.0.0:8100 */
    public PreviewServer(Ferma plugin, String bind) throws IOException {
        this.plugin = plugin;
        Properties props = new Properties();
        try (InputStream in = plugin.getResource("preview/biome_colors.properties")) {
            props.load(in);
        }
        props.forEach((k, v) -> colors.put((String) k, Integer.parseInt((String) v, 16)));
        int colon = bind.lastIndexOf(':');
        http = HttpServer.create(new InetSocketAddress(bind.substring(0, colon), Integer.parseInt(bind.substring(colon + 1))), 0);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        http.start();
        plugin.getLogger().info("Seed preview listening on " + bind);
    }

    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String[] p = ex.getRequestURI().getPath().split("/");
            if (!"GET".equals(ex.getRequestMethod()) || p.length != 7 || !p[0].isEmpty() || !p[6].endsWith(".png")) {
                send(ex, 400, "usage: GET /<type>/<pack>/<seed>/<z>/<x>/<y>.png");
                return;
            }
            ResourceKey<LevelStem> type = TYPES.get(p[1]);
            if (type == null) {
                send(ex, 404, "unknown type '" + p[1] + "', valid: " + TYPES.keySet());
                return;
            }
            FermaPack pack = plugin.getPack(p[2]);
            if (pack == null || !"noise".equals(pack.type())) {
                send(ex, 404, "unknown or non-noise pack '" + p[2] + "'");
                return;
            }
            long seed;
            int z, x, y;
            try {
                seed = Long.parseLong(p[3]);
                z = Integer.parseInt(p[4]);
                x = Integer.parseInt(p[5]);
                y = Integer.parseInt(p[6].substring(0, p[6].length() - 4));
            } catch (NumberFormatException e) {
                send(ex, 400, "seed, z, x and y must be integers");
                return;
            }
            if (z < 0 || z > MAX_ZOOM) {
                send(ex, 400, "z must be 0.." + MAX_ZOOM);
                return;
            }
            long span = (long) TILE << (2 + MAX_ZOOM - z); // blocks per tile edge
            if (Math.abs(x * span) > WORLD_LIMIT || Math.abs(y * span) > WORLD_LIMIT) {
                send(ex, 404, "tile is outside the world border");
                return;
            }

            byte[] png;
            try {
                png = render(preview(p[1] + "/" + p[2] + "/" + seed, type, pack, seed), z, x, y);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Seed preview failed for " + ex.getRequestURI(), e);
                send(ex, 500, "preview failed: " + e.getMessage());
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(png);
            }
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private Preview preview(String key, ResourceKey<LevelStem> type, FermaPack pack, long seed) throws ReflectiveOperationException {
        synchronized (previews) {
            Preview cached = previews.get(key);
            if (cached != null) {
                return cached;
            }
            MinecraftServer server = MinecraftServer.getServer();
            // CraftServer.createWorld's new-world path: the "normal" preset with no generator
            // settings (as Cardinal's books create worlds), baked over the datapack-defined
            // dimensions. ChunkMap builds RandomState from the server's NOISE registry.
            WorldLoader.DataLoadContext context = server.worldLoaderContext;
            LevelStem stem = new DedicatedServerProperties.WorldDimensionData(new JsonObject(), "normal")
                .create(context.datapackWorldgen())
                .bake(context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM))
                .dimensionsRegistryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(type);
            if (!(stem.generator() instanceof NoiseBasedChunkGenerator generator)) {
                throw new IllegalStateException("type " + type.identifier() + " is not noise-based: "
                    + stem.generator().getClass().getName());
            }
            GraphSurgeryDiagnostic.validateVanillaStructure(plugin.getLogger()); // primary gate, as at world creation
            RandomState randomState = RandomState.create(
                generator.generatorSettings().value(), server.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            if (!pack.climate().isEmpty()) { // same condition as NMSInjectListener
                NMSInjectListener.patchRandomState(randomState, generator.generatorSettings(), seed, pack,
                    "preview '" + key + "'", plugin.getLogger());
            }
            NoiseSettings noise = generator.generatorSettings().value().noiseSettings();
            Preview built = new Preview(randomState, generator.getBiomeSource(), noise.minY(), noise.minY() + noise.height() - 1);
            previews.put(key, built);
            return built;
        }
    }

    private byte[] render(Preview w, int z, int tx, int ty) throws IOException {
        int bpp = 4 << (MAX_ZOOM - z); // blocks per pixel; always a multiple of 4, so columns are quart-aligned
        int n = TILE + 1;              // one extra west column and north row, for shading neighbours
        long x0 = ((long) tx * TILE - 1) * bpp;
        long z0 = ((long) ty * TILE - 1) * bpp;
        DensityFunction surface = w.randomState().router().preliminarySurfaceLevel();
        int[] h = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // Vanilla's own evaluation (NoiseChunk.computePreliminarySurfaceLevel): y=0, floored.
                h[j * n + i] = Mth.floor(surface.compute(new DensityFunction.SinglePointContext(
                    (int) (x0 + (long) i * bpp), 0, (int) (z0 + (long) j * bpp))));
            }
        }
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        for (int j = 1; j < n; j++) {
            for (int i = 1; i < n; i++) {
                int bx = (int) (x0 + (long) i * bpp);
                int bz = (int) (z0 + (long) j * bpp);
                int height = h[j * n + i];
                // Biome at the surface, not at a fixed y, so cave biomes don't show through.
                Holder<Biome> biome = w.biomes().getNoiseBiome(QuartPos.fromBlock(bx),
                    QuartPos.fromBlock(Mth.clamp(height, w.minY(), w.maxY())), QuartPos.fromBlock(bz), w.randomState().sampler());
                img.setRGB(i - 1, j - 1, shade(color(biome), height, h[j * n + i - 1], h[(j - 1) * n + i], bpp));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private int color(Holder<Biome> biome) {
        Identifier id = biome.unwrapKey().orElseThrow().identifier();
        Integer rgb = "minecraft".equals(id.getNamespace()) ? colors.get(id.getPath()) : null;
        if (rgb == null) {
            throw new IllegalStateException("no preview color for biome " + id);
        }
        return rgb;
    }

    // wozniak: our own take on mcseedmap's STEPPED shading + SIMPLE contours, not a port of its
    // shader. Ceiling: similar look, not pixel-identical. Upgrade: port a reference height shader.
    private static int shade(int rgb, int y, int west, int north, int bpp) {
        int band = Math.floorDiv(y, STEP);
        // Light from the north-west: a pixel higher than its west/north neighbours faces the light.
        int rise = Mth.clamp((band - Math.floorDiv(west, STEP)) + (band - Math.floorDiv(north, STEP)), -3, 3);
        double f = 1.0 + 0.1 * rise;
        int line = Math.floorDiv(y, CONTOUR);
        if (bpp <= CONTOUR_MAX_BPP && (line != Math.floorDiv(west, CONTOUR) || line != Math.floorDiv(north, CONTOUR))) {
            f *= 0.7;
        }
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * f));
        int g = Math.min(255, (int) ((rgb >> 8 & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }
}
