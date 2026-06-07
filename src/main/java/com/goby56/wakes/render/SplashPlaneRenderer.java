package com.goby56.wakes.render;

import com.goby56.wakes.config.WakesConfig;
import com.goby56.wakes.config.enums.Resolution;
import com.goby56.wakes.duck.ProducesWake;
import com.goby56.wakes.particle.custom.SplashPlaneParticle;
import com.goby56.wakes.simulation.WakeHandler;
import com.goby56.wakes.utils.WakesUtils;
import com.mojang.blaze3d.vertex.*;
import io.github.jdiemke.triangulation.DelaunayTriangulator;
import io.github.jdiemke.triangulation.NotEnoughPointsException;
import io.github.jdiemke.triangulation.Triangle2D;
import io.github.jdiemke.triangulation.Vector2D;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SplashPlaneRenderer implements LevelRenderEvents.AfterTranslucentTerrain, LevelRenderEvents.BeforeTranslucentTerrain {

    private static ArrayList<Vector2D> points;
    private static List<Triangle2D> triangles;
    private static ArrayList<Vec3> vertices;
    private static ArrayList<Vec3> normals;

    public static Map<Resolution, SplashPlaneTexture> wakeTextures = null;

    private static void initTextures() {
        wakeTextures = Map.of(
                Resolution.EIGHT, new SplashPlaneTexture(Resolution.EIGHT.res),
                Resolution.SIXTEEN, new SplashPlaneTexture(Resolution.SIXTEEN.res),
                Resolution.THIRTYTWO, new SplashPlaneTexture(Resolution.THIRTYTWO.res)
        );
    }

    private static final double SQRT_8 = Math.sqrt(8);

    private static boolean cameraSubmerged(LevelRenderContext context) {
        return context.gameRenderer().getMainCamera().getFluidInCamera()
                == net.minecraft.world.level.material.FogType.WATER;
    }

    // Splash planes are 3-D sheets rising above the water. Normally render them after the
    // translucent water (unchanged behavior above water). But when the camera is underwater,
    // the water surface would occlude them, so render BEFORE translucent water instead: opaque
    // terrain (already drawn) still occludes them correctly, while the water draws afterwards
    // and, being translucent, lets the splash plane show through from below.
    @Override
    public void afterTranslucentTerrain(LevelRenderContext context) {
        if (cameraSubmerged(context)) return;
        renderAll(context);
    }

    @Override
    public void beforeTranslucentTerrain(LevelRenderContext context) {
        if (!cameraSubmerged(context)) return;
        renderAll(context);
    }

    private void renderAll(LevelRenderContext context) {
        if (WakeHandler.getInstance().isEmpty()) {
            return;
        }
        WakeHandler wakeHandler = WakeHandler.getInstance().get();
        for (SplashPlaneParticle particle : wakeHandler.getVisibleSplashPlanes()) {
            SplashPlaneRenderer.render(particle.owner, particle, context, new PoseStack());
        }
    }


    public static <T extends Entity> void render(T entity, SplashPlaneParticle splashPlane, LevelRenderContext context, PoseStack matrices) {
        if (wakeTextures == null) initTextures();
        if (WakesConfig.disableMod || !WakesUtils.getEffectRuleFromSource(entity).renderPlanes) {
            return;
        }
        if (Minecraft.getInstance().options.getCameraType().isFirstPerson() &&
                !WakesConfig.firstPersonEffects &&
                splashPlane.owner instanceof LocalPlayer) {
            return;
        }
        splashPlane.updateYaw(context.gameRenderer().getMainCamera().getCameraEntityPartialTicks(net.minecraft.client.Minecraft.getInstance().getDeltaTracker()));

        matrices.pushPose();
        splashPlane.translateMatrix(context.gameRenderer().getMainCamera(), matrices);
        matrices.mulPose(Axis.YP.rotationDegrees(splashPlane.lerpedYaw + 180f));
        float velocity = (float) Math.floor(((ProducesWake) entity).wakes$getHorizontalVelocity() * 20) / 20f;
        float progress = Math.min(1f, velocity / WakesConfig.maxSplashPlaneVelocity);
        float scalar = (float) (WakesConfig.splashPlaneScale * Math.sqrt(entity.getBbWidth() * Math.max(1f, progress) + 1) / 3f);
        matrices.scale(scalar, scalar, scalar);
        Matrix4f matrix = matrices.last().pose();
        matrices.popPose();

        SplashPlaneTexture texture = wakeTextures.get(WakeHandler.resolution);
        if (texture.resolution != splashPlane.image.getWidth()) {
            return;
        }
        texture.loadTexture(splashPlane.image);
        renderSurface(matrix, texture, context.bufferSource());
    }

    private static void renderSurface(Matrix4f matrix, SplashPlaneTexture splashTexture, MultiBufferSource.BufferSource bufferSource) {
        RenderType type = RenderTypes.entityTranslucentEmissive(splashTexture.id);
        VertexConsumer vc = bufferSource.getBuffer(type);
        for (int s = -1; s < 2; s++) {
            if (s == 0) continue;
            for (int i = 0; i < vertices.size(); i += 3) {
                Vec3 v0 = vertices.get(i);
                Vec3 n0 = normals.get(i);
                Vec3 v1 = vertices.get(i + 1);
                Vec3 n1 = normals.get(i + 1);
                Vec3 v2 = vertices.get(i + 2);
                Vec3 n2 = normals.get(i + 2);
                addDegenerateQuad(vc, matrix, s, v0, n0, v1, n1, v2, n2);
                addDegenerateQuad(vc, matrix, s, v0, n0, v2, n2, v1, n1);
            }
        }
        // The splash plane textures are shared per resolution, so flush this particle's
        // geometry now before the next particle overwrites the texture.
        bufferSource.endBatch(type);
    }

    private static void addVertex(VertexConsumer vc, Matrix4f matrix, int side, Vec3 vertex, Vec3 normal) {
        vc.addVertex(matrix,
                        (float) (side * (vertex.x * WakesConfig.splashPlaneWidth + WakesConfig.splashPlaneGap)),
                        (float) (vertex.z * WakesConfig.splashPlaneHeight),
                        (float) (vertex.y * WakesConfig.splashPlaneDepth))
                .setColor(1f, 1f, 1f, 1f)
                .setUv((float) vertex.x, (float) vertex.y)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void addDegenerateQuad(VertexConsumer vc, Matrix4f matrix, int side, Vec3 a, Vec3 an, Vec3 b, Vec3 bn, Vec3 c, Vec3 cn) {
        addVertex(vc, matrix, side, a, an);
        addVertex(vc, matrix, side, b, bn);
        addVertex(vc, matrix, side, c, cn);
        addVertex(vc, matrix, side, c, cn);
    }

    private static double upperBound(double x) {
        return - 2 * x * x + SQRT_8 * x;
    }

    private static double lowerBound(double x) {
        return (SQRT_8 - 2) * x * x;
    }

    private static double height(double x, double y) {
        return 4 * (x * (SQRT_8 - x) - y - x * x) / SQRT_8;
    }

    private static Vec3 normal(double x, double y) {
        double nx = SQRT_8 / (4 * (4 * x + y - SQRT_8));
        double ny = SQRT_8 / (4 * (2 * x * x - SQRT_8 + 1));
        return Vec3.directionFromRotation((float) Math.tan(nx), (float) Math.tan(ny));
    }

    private static void distributePoints() {
        int res = WakesConfig.splashPlaneResolution;
        points = new ArrayList<>();

        for (float i = 0; i < res; i++) {
            double x = i / (res - 1);
            double h = upperBound(x) - lowerBound(x);
            int nPoints = (int) Math.max(1, Math.floor(h * res));
            for (float j = 0; j < nPoints + 1; j++) {
                float y = (float) ((j / nPoints) * h + lowerBound(x));
                points.add(new Vector2D(x, y));
            }
        }
    }

    private static void generateMesh() {
        vertices = new ArrayList<>();
        normals = new ArrayList<>();
        try {
            DelaunayTriangulator delaunay = new DelaunayTriangulator(points);
            delaunay.triangulate();
            triangles = delaunay.getTriangles();
        } catch (NotEnoughPointsException e) {
            e.printStackTrace();
        }
        for (Triangle2D tri : triangles) {
            for (Vector2D vec : new Vector2D[] {tri.a, tri.b, tri.c}) {
                double x = vec.x;
                double y = vec.y;
                vertices.add(new Vec3(x, y, height(x, y)));
                normals.add(normal(x, y));
            }
        }
    }

    public static void initSplashPlane() {
        distributePoints();
        generateMesh();
    }
}
