package com.goby56.wakes.render;

import com.goby56.wakes.WakesClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.layer.GbufferPrograms;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.minecraft.world.level.block.Blocks;

/**
 * Thin bridge to Iris internals. All methods are guarded so the Iris classes are only
 * touched when Iris is actually present (the methods are never called otherwise, so the
 * JVM never links the Iris references on installs without Iris).
 *
 * The point: force our wake draw through Iris's translucent-terrain (water) program so the
 * shaderpack applies the SAME vertex wave displacement to the wake that it applies to the
 * water. That makes wakes follow the shader's waves instead of floating at a fixed height.
 */
public final class IrisCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    /** Override Iris's program phase to the water/translucent-terrain program. */
    public static void beginWaterProgram() {
        try {
            GbufferPrograms.setOverridePhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
        } catch (Throwable t) {
            // Iris internal API changed or unavailable; fail safe (wakes still render, just
            // without wave-following).
            WakesClient.LOGGER.debug("IrisCompat.beginWaterProgram failed", t);
        }
    }

    /** Clear the program-phase override. */
    public static void endWaterProgram() {
        try {
            GbufferPrograms.setOverridePhase(null);
        } catch (Throwable t) {
            WakesClient.LOGGER.debug("IrisCompat.endWaterProgram failed", t);
        }
    }

    /** The shaderpack's block ID for water (used as mc_Entity), or -1 if unavailable. */
    public static int waterBlockId() {
        try {
            var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
            if (ids == null) return -1;
            return ids.getInt(Blocks.WATER.defaultBlockState());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Tag subsequently-emitted vertices with water's block ID so the shader's water-detection
     * (mc_Entity) passes and it applies the wave displacement. No-op if the buffer isn't an
     * Iris BlockSensitiveBufferBuilder or water has no mapped id.
     */
    public static void beginWaterBlock(VertexConsumer vc) {
        try {
            int id = waterBlockId();
            if (id != -1 && vc instanceof BlockSensitiveBufferBuilder b) {
                b.overrideBlock(id);
            }
        } catch (Throwable t) {
            WakesClient.LOGGER.debug("IrisCompat.beginWaterBlock failed", t);
        }
    }

    public static void endWaterBlock(VertexConsumer vc) {
        try {
            if (vc instanceof BlockSensitiveBufferBuilder b) {
                b.restoreBlock();
            }
        } catch (Throwable t) {
            WakesClient.LOGGER.debug("IrisCompat.endWaterBlock failed", t);
        }
    }
}
