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
            NiloreClient.getInstance().getEventBus().call(new RenderEvent(poseStack, partialTick));
        }
    }

    /**
     * BetterFPS 的 "减少渲染方块"。
     *
     * <p>{@code renderChunkLayer} 的循环体是：
     * <pre>
     *   RenderChunk chunk = info.chunk;
     *   if (chunk.getCompiledChunk().isEmpty(renderType)) continue;   // ← 这里
     *   VertexBuffer buffer = chunk.getBuffer(renderType);
     *   ...draw...
     * </pre>
     * {@code CompiledChunk.isEmpty} 就是 {@code !layers.contains(renderType)}，所以让超距的
     * section 拿到静态的 {@code UNCOMPILED}（layers 永远为空）等价于 "continue"：这个 section
     * 本帧不绑定、不绘制顶点。选择这个点而不是自己改循环，是因为它在方法里只有一处调用，
     * 且不需要重写 {@code renderChunkLayer} 的局部变量表。
     *
     * <p>只影响 {@code renderChunkLayer}：{@code renderLevel} 里渲染方块实体的那段另外调用了
     * 一次 {@code getCompiledChunk()}，那条路径仍然照常工作（方块实体不会凭空消失）。
     */
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
        if (betterFPS != null) {
            ChunkRenderDispatcher.RenderChunk chunk = original.instance();
            if (chunk != null && betterFPS.shouldSkipSection(chunk.getOrigin())) {
                return ChunkRenderDispatcher.CompiledChunk.UNCOMPILED;
            }
        }
        return original.call();
    }
}
