package com.goby56.wakes.render;

import com.goby56.wakes.WakesClient;
import com.goby56.wakes.config.WakesConfig;
import com.goby56.wakes.simulation.WakeChunk;
import com.goby56.wakes.simulation.WakeHandler;
import com.goby56.wakes.simulation.WakeNode;
import com.goby56.wakes.debug.WakesDebugInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.model.geom.builders.UVPair;
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
        return RenderTypes.entityTranslucentEmissive(WakeTextureAtlas.ATLAS_ID);
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

            PoseStack matrices = context.poseStack();
            matrices.pushPose();
            matrices.translate(-camera.x, -camera.y, -camera.z);
            Matrix4fc matrix = matrices.last().pose();

            MultiBufferSource.BufferSource bufferSource = context.bufferSource();
            RenderType type = renderType();
            VertexConsumer vc = bufferSource.getBuffer(type);

            addVertices(matrix, vc, wakeChunks);

            matrices.popPose();

            // Flush immediately so it draws into the currently-bound (Iris) framebuffer
            bufferSource.endBatch(type);
        } catch (Throwable t) {
            WakesClient.LOGGER.error("WakeRenderer: EXCEPTION during render", t);
        }

        WakesDebugInfo.renderingTime.add(System.nanoTime() - tRendering);
        WakesDebugInfo.quadsRendered = wakeChunks.size();
    }

    private void addVertices(Matrix4fc matrix, VertexConsumer vc, List<WakeChunk> chunks) {
        for (WakeChunk wakeChunk : chunks) {
            UVPair uv = wakeChunk.drawContext.getUV();
            float uvOffset = wakeChunk.drawContext.getUVOffset();

            float x0 = (float) wakeChunk.pos.x;
            float y = (float) (wakeChunk.pos.y + WakeNode.WATER_OFFSET);
            float z0 = (float) wakeChunk.pos.z;
            float x1 = x0 + WakeChunk.WIDTH;
            float z1 = z0 + WakeChunk.WIDTH;

            emitQuad(vc, matrix, x0, z0, x1, z1, y,
                    uv.u(), uv.v(), uv.u() + uvOffset, uv.v() + uvOffset);
        }
    }

    /** Emits a horizontal quad (both windings) at height y spanning [x0,z0]..[x1,z1] with the given UV rect. */
    private void emitQuad(VertexConsumer vc, Matrix4fc m,
                          float x0, float z0, float x1, float z1, float y,
                          float u0, float v0, float u1, float v1) {
        // top-facing (CCW seen from above)
        vert(vc, m, x0, y, z0, u0, v0, 1f);
        vert(vc, m, x0, y, z1, u0, v1, 1f);
        vert(vc, m, x1, y, z1, u1, v1, 1f);
        vert(vc, m, x1, y, z0, u1, v0, 1f);
        // bottom-facing (reverse winding) to defeat backface culling
        vert(vc, m, x1, y, z0, u1, v0, -1f);
        vert(vc, m, x1, y, z1, u1, v1, -1f);
        vert(vc, m, x0, y, z1, u0, v1, -1f);
        vert(vc, m, x0, y, z0, u0, v0, -1f);
    }

    private void vert(VertexConsumer vc, Matrix4fc m, float x, float y, float z, float u, float v, float ny) {
        vc.addVertex(m, x, y, z)
                .setColor(1f, 1f, 1f, 1f) // white: let the atlas texture provide the wake color/alpha
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(0f, ny, 0f);
    }

    public void close() {
    }
}
