package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.FirstPersonModel;

/**
 * FirstPersonModel 的配套: 第一人称补画自己时, 别把头盔也画进去。
 *
 * <p>第一人称相机在眼位, 也就是头部立方体内部。FirstPersonModel 会把
 * {@code PlayerModel.head/hat} 的 {@code visible} 置 false, 但头盔是
 * {@link HumanoidArmorLayer} 用自己的 {@code innerModel/outerModel} 画的,
 * 与玩家模型那个 head 部件没有关系 —— 只藏皮肤头, 视野里剩下的就是头盔内壁。
 * 实体渲染用的是 {@code entityCutoutNoCull}(不剔除背面), 所以从内部看过去是一整片
 * 贴图铺满屏幕, 表现就是"第一人称只看得见自己的头"。</p>
 *
 * <p>只取消 HEAD 那一块, 胸/腿/脚盔甲照常; 只在"自己 + 第一人称 + FirstPersonModel
 * 开着 + Hide Head 开着"时生效, 第三人称看自己和看别人都不受影响。</p>
 */
@Patch(HumanoidArmorLayer.class)
public class HumanoidArmorLayerPatch {
    @Inject(
            method = "renderArmorPiece",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;I"
                    + "Lnet/minecraft/client/model/HumanoidModel;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderArmorPiece(
            HumanoidArmorLayer<?, ?, ?> layer,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            LivingEntity entity,
            EquipmentSlot slot,
            int packedLight,
            HumanoidModel<?> model,
            CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady() || slot != EquipmentSlot.HEAD) {
            return;
        }
        if (FirstPersonModel.skipOwnHeadLayers(entity)) {
            callbackInfo.cancel();
        }
    }
}
