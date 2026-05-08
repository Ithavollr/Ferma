package org.evlis.firma;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;
import xyz.jpenilla.reflectionremapper.proxy.ReflectionProxyFactory;
import xyz.jpenilla.reflectionremapper.proxy.annotation.FieldGetter;
import xyz.jpenilla.reflectionremapper.proxy.annotation.FieldSetter;
import xyz.jpenilla.reflectionremapper.proxy.annotation.Proxies;

/**
 * Reflection utilities for accessing obfuscated NMS fields.
 * Uses reflection-remapper to handle obfuscation mapping.
 */
public class Reflection {
    public static final ChunkMapProxy CHUNKMAP;

    static {
        ReflectionRemapper reflectionRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();
        ReflectionProxyFactory reflectionProxyFactory = ReflectionProxyFactory.create(reflectionRemapper,
                Reflection.class.getClassLoader());

        CHUNKMAP = reflectionProxyFactory.reflectionProxy(ChunkMapProxy.class);
    }

    /**
     * Proxy interface for accessing ChunkMap's WorldGenContext field.
     * This is the critical field we need to modify to inject our generator.
     */
    @Proxies(ChunkMap.class)
    public interface ChunkMapProxy {
        @FieldGetter("worldGenContext")
        WorldGenContext getWorldGenContext(ChunkMap instance);

        @FieldSetter("worldGenContext")
        void setWorldGenContext(ChunkMap instance, WorldGenContext worldGenContext);
    }
}
