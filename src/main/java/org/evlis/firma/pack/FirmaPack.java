package org.evlis.firma.pack;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * A parsed Firma pack containing climate function configurations.
 * Packs are loaded from plugins/Firma/packs/<pack_id>/pack.yml
 */
public record FirmaPack(
    int schemaVersion,
    String id,
    String name,
    String description,
    String type,
    Map<String, ClimateFunctionConfig> climate,
    @Nullable VoidPalette voidPalette
) {
    
    /**
     * Create a pack with validation.
     */
    public FirmaPack {
        Objects.requireNonNull(id, "Pack id cannot be null");
        if (!id.matches("^[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Pack id must match [a-z0-9_]+, got: " + id);
        }
        
        // Check reserved names
        if (isReservedName(id)) {
            throw new IllegalArgumentException("Pack id '" + id + "' is a reserved name");
        }
        
        // Default values
        if (name == null || name.isEmpty()) {
            name = id;
        }
        if (description == null) {
            description = "";
        }
        if (climate == null) {
            climate = Collections.emptyMap();
        } else {
            // Make immutable copy
            climate = Collections.unmodifiableMap(new HashMap<>(climate));
        }
        // voidPalette can be null (for non-void packs or void packs without a palette)
    }
    
    /**
     * Check if a pack id is a reserved name.
     */
    public static boolean isReservedName(String id) {
        return id.equals("vanilla") || id.equals("void") || id.equals("noise");
    }
    
    /**
     * Get the climate function config for a parameter, defaulting to identity.
     */
    public ClimateFunctionConfig getClimateConfig(String parameter) {
        return climate.getOrDefault(parameter, new ClimateFunctionConfig.Identity());
    }
    
    /**
     * Check if this pack has an explicit configuration for a parameter.
     */
    public boolean hasClimateConfig(String parameter) {
        return climate.containsKey(parameter);
    }
    
    /**
     * Create a passthrough pack (all identity).
     */
    public static FirmaPack passthrough() {
        return new FirmaPack(1, "passthrough", "Passthrough", "Bit-identical vanilla climate", "noise", Collections.emptyMap(), null);
    }
}
