package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import client.nilore.NiloreClient;
import client.nilore.asm.Invocation;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.impl.render.BetterFPS;

@Patch(LevelRenderer.class)
public class LevelRendererPatch {
    @Inject(
            method = "renderLevel",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(At.Type.TAIL)
    )
    public static void onRenderLevel(
            LevelRenderer renderer,
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projection,
            CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            // Frame boundary for the frame-scoped blur capture: everything drawn after this point
            // (vanilla GUI, HUD, screens) shares one downsample of the framebuffer.
            client.nilore.utils.render.RenderUtil.beginFrame();
            NiloreClient.getInstance().getEventBus().call(new RenderEvent(poseStack, partialTick));
        }
    }

    /**
     * 天空、云、天气三项的开关。三者都是纯视觉内容, 直接取消原方法即可, 不影响任何游戏逻辑。
     *
     * <p>描述符全部对着 1.20.1 官方映射核对过:
     * renderSky(PoseStack, Matrix4f, float, Camera, boolean, Runnable)、
     * renderClouds(PoseStack, Matrix4f, float, double, double, double)、
     * renderSnowAndRain(LightTexture, float, double, double, double)。</p>
     */
    @Inject(
            method = "renderSky",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderSky(
            LevelRenderer renderer,
            PoseStack poseStack,
            Matrix4f projection,
            float partialTick,
            Camera camera,
            boolean isFoggy,
            Runnable setupFog,
            CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.hidesSky()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderClouds",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderClouds(
            LevelRenderer renderer,
            PoseStack poseStack,
            Matrix4f projection,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.hidesClouds()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderSnowAndRain",
            desc = "(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderWeather(
            LevelRenderer renderer,
            LightTexture lightTexture,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.hidesWeather()) {
            callbackInfo.cancel();
        }
    }

    @WrapInvoke(
            method = "renderChunkLayer",
            desc = "(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            target = "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk/getCompiledChunk",
            targetDesc = "()Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;"
    )
    public static ChunkRenderDispatcher.CompiledChunk onRenderSectionChunk(
            LevelRenderer renderer,
            RenderType renderType,
            PoseStack poseStack,
            double camX,
            double camY,
            double camZ,
            Matrix4f projection,
            Invocation<ChunkRenderDispatcher.RenderChunk, ChunkRenderDispatcher.CompiledChunk> original)
            throws Exception {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        ChunkRenderDispatcher.RenderChunk chunk = original.instance();
        if (chunk != null && betterFPS != null) {
            BlockPos origin = chunk.getOrigin();
            double dx = origin.getX() + 8.0 - camX;
            double dz = origin.getZ() + 8.0 - camZ;
            if (betterFPS.covers(origin, dx * dx + dz * dz)) {
                return ChunkRenderDispatcher.CompiledChunk.UNCOMPILED;
            }
        }
        return original.call();
    }
}
