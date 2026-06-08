package com.goby56.wakes.render;

import com.goby56.wakes.WakesClient;
import com.goby56.wakes.config.WakesConfig;
import com.goby56.wakes.simulation.WakeChunk;
import com.goby56.wakes.simulation.WakeHandler;
import com.goby56.wakes.simulation.WakeNode;
import com.goby56.wakes.debug.WakesDebugInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import java.util.*;

public class WakeRenderer implements LevelRenderEvents.AfterTranslucentTerrain {

    private static RenderType renderType() {
        return RenderTypes.entityTranslucent(WakeTextureAtlas.ATLAS_ID, false);
    }

    @Override
    public void afterTranslucentTerrain(LevelRenderContext context) {
        if (WakesConfig.disableMod) {
            WakesDebugInfo.quadsRendered = 0;
            return;
        }

        WakeHandler wakeHandler = WakeHandler.getInstance().orElse(null);
        if (wakeHandler == null) {
            return;
        }

        List<WakeChunk> wakeChunks = wakeHandler.getVisibleChunks();
        if (wakeChunks.isEmpty()) {
            return;
        }

        long tRendering = System.nanoTime();
        try {
            // Make sure the GPU texture has the latest simulation pixels
            wakeHandler.getTextureAtlas().dynamicTexture.uploadIfDirty();

            Vec3 camera = context.levelState().cameraRenderState.pos;

            // Is the camera underwater? Pick which side of the water surface to place the
            // single wake plane so it isn't depth-occluded by the water for this viewpoint.
            boolean cameraSubmerged = context.gameRenderer().getMainCamera().getFluidInCamera()
                    == net.minecraft.world.level.material.FogType.WATER;

            PoseStack matrices = context.poseStack();
            matrices.pushPose();
            matrices.translate(-camera.x, -camera.y, -camera.z);
            Matrix4fc matrix = matrices.last().pose();

            MultiBufferSource.BufferSource bufferSource = context.bufferSource();
            RenderType type = renderType();
            VertexConsumer vc = bufferSource.getBuffer(type);

            // When a shaderpack is active, make the wake follow the shader's water waves:
            //  1) tag the vertices with water's block id (mc_Entity) so the shader's
            //     water-detection passes, and
            //  2) route the draw through the water program where the wave displacement lives.
            boolean useWaterProgram = WakesClient.areShadersEnabled && IrisCompat.isLoaded();

            if (useWaterProgram) IrisCompat.beginWaterBlock(vc);
            // Subdivide into a grid only when the shader is displacing the wake, so it follows
            // the wave curve. Without shaders a single flat quad is fine (and cheaper).
            addVertices(matrix, vc, wakeChunks, cameraSubmerged, useWaterProgram);
            if (useWaterProgram) IrisCompat.endWaterBlock(vc);

            matrices.popPose();

            if (useWaterProgram) IrisCompat.beginWaterProgram();
            // Flush immediately so it draws into the currently-bound (Iris) framebuffer
            bufferSource.endBatch(type);
            if (useWaterProgram) IrisCompat.endWaterProgram();
        } catch (Throwable t) {
            WakesClient.LOGGER.error("WakeRenderer: EXCEPTION during render", t);
        }

        WakesDebugInfo.renderingTime.add(System.nanoTime() - tRendering);
        WakesDebugInfo.quadsRendered = wakeChunks.size();
    }

    private static final float SURFACE_EPSILON = 0.06f;
    // When submerged, drop the plane clearly below the water surface so it isn't occluded
    // by the water's depth when looking up from underwater.
    private static final float UNDERWATER_DROP = 0.15f;

    private void addVertices(Matrix4fc matrix, VertexConsumer vc, List<WakeChunk> chunks,
                             boolean cameraSubmerged, boolean irisWaterBlock) {
        // Height of the wake plane relative to the water surface:
        //  - underwater: drop well below so the water doesn't occlude it from below.
        //  - shader water: coplanar (0). The wake rides the same waves as the water and draws
        //    after it in the same program, so it overlays the water surface like a foam mask
        //    instead of floating above it (which looked like clipping on wave crests).
        //  - no shaders / flat water: a tiny lift to avoid z-fighting the flat surface.
        float surfaceBias;
        if (cameraSubmerged) {
            surfaceBias = -UNDERWATER_DROP;
        } else if (irisWaterBlock) {
            surfaceBias = 0f;
        } else {
            surfaceBias = SURFACE_EPSILON;
        }
        ClientLevel world = Minecraft.getInstance().level;
        for (WakeChunk wakeChunk : chunks) {
            for (WakeNode wakeNode : wakeChunk.getNodes()) {
                UVPair uv = wakeNode.drawContext.getUV();
                float uvOffset = wakeNode.drawContext.getUVOffset();
                int light = LevelRenderer.getLightCoords(world, wakeNode.blockPos());

                float x0 = (float) wakeNode.x;
                float y = (float) (wakeNode.y + WakeNode.WATER_OFFSET) + surfaceBias;
                float z0 = (float) wakeNode.z;

                emitQuad(vc, matrix,
                        x0, z0,
                        x0 + 1, z0 + 1,
                        y,
                        uv.u(), uv.v(),
                        uv.u() + uvOffset,  uv.v() + uvOffset,
                        light
                );
            }
        }
    }

    /** Emits a single horizontal quad (both windings, same height) spanning [x0,z0]..[x1,z1]. */
    private void emitQuad(VertexConsumer vc, Matrix4fc m,
                          float x0, float z0, float x1, float z1, float y,
                          float u0, float v0, float u1, float v1, int light) {
        // top-facing (CCW seen from above)
        vert(vc, m, x0, y, z0, u0, v0, 1f, light);
        vert(vc, m, x0, y, z1, u0, v1, 1f, light);
        vert(vc, m, x1, y, z1, u1, v1, 1f, light);
        vert(vc, m, x1, y, z0, u1, v0, 1f, light);
        // bottom-facing (reverse winding) so it's also visible from below
        vert(vc, m, x1, y, z0, u1, v0, -1f, light);
        vert(vc, m, x1, y, z1, u1, v1, -1f, light);
        vert(vc, m, x0, y, z1, u0, v1, -1f, light);
        vert(vc, m, x0, y, z0, u0, v0, -1f, light);
    }

    private void vert(VertexConsumer vc, Matrix4fc m, float x, float y, float z, float u, float v, float ny, int light) {
        vc.addVertex(m, x, y, z)
                .setColor(1f, 1f, 1f, 1f) // white: let the atlas texture provide the wake color/alpha
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0f, ny, 0f);
    }

    public void close() {
    }
}
