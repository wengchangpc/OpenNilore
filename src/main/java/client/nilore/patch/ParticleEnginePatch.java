package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import client.nilore.modules.impl.render.BetterFPS;

/**
 * 粒子的两个挂点。
 *
 * <p>{@code render} 是整帧粒子绘制的唯一入口, 取消它就是"完全不画粒子"。</p>
 *
 * <p>{@code add} 才是数量上限的落点: 粒子死亡是异步的, 只在绘制时丢并不能阻止粒子
 * 继续堆积占内存, 也不能阻止它继续参与 tick。拦在生成处, 超限就不再接受新粒子,
 * 内存和 CPU 一起省。</p>
 */
@Patch(ParticleEngine.class)
public class ParticleEnginePatch {

    @Inject(
            method = "render",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRender(
            ParticleEngine engine,
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            LightTexture lightTexture,
            Camera camera,
            float partialTick,
            CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.hidesParticles()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "add",
            desc = "(Lnet/minecraft/client/particle/Particle;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onAdd(ParticleEngine engine, Particle particle, CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.rejectsParticle(engine)) {
            callbackInfo.cancel();
        }
    }
}
