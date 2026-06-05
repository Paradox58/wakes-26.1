package com.goby56.wakes.render;

import com.goby56.wakes.WakesClient;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public class SplashPlaneTexture extends AbstractTexture {
    public final int resolution;
    public final Identifier id;

    public SplashPlaneTexture(int resolution) {
        this.resolution = resolution;
        this.id = Identifier.fromNamespaceAndPath(WakesClient.MOD_ID, "splash_plane_" + resolution);

        this.texture = RenderSystem.getDevice().createTexture(() -> WakesClient.MOD_ID + " splash plane texture",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, resolution, resolution, 1, 1);
        this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        this.textureView = RenderSystem.getDevice().createTextureView(this.texture);

        Minecraft.getInstance().getTextureManager().register(this.id, this);
    }

    public void loadTexture(NativeImage image) {
        if (image == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, image);
    }
}
