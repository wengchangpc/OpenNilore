package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import client.nilore.modules.impl.render.BetterFPS;

/**
 * 方块实体 (箱子、告示牌、熔炉……) 的渲染剔除。
 *
 * <p>挂的是 {@code BlockEntityRenderDispatcher.render}, 也就是每个方块实体渲染的
 * 唯一入口。取消它就是整个跳过, 连 {@code setupAndRender} 里的模型姿态计算都不会跑。</p>
 *
 * <p>没有挂在 {@code LevelRenderer} 的 block entity 循环上, 是因为那个循环是按
 * 可见 section 组织的, 在里面插入需要依赖 renderLevel 的内部结构; 挂在分发器上
 * 跟内部结构无关, 也不会随版本变动而失配。</p>
 */
@Patch(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherPatch {

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRender(
            BlockEntityRenderDispatcher dispatcher,
            BlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfo callbackInfo) {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.shouldCullBlockEntity(blockEntity)) {
            callbackInfo.cancel();
        }
    }
}
