package client.nilore.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;

/**
 * FirstPersonModel —— 第一人称看全身: 低头能看到自己的腿, 潜行能看到自己的姿态。
 *
 * <h2>实现</h2>
 * <p>原版第一人称不渲染本地玩家模型 (只画 ItemInHandRenderer 的手)。本模块在
 * RenderEvent (renderLevel 尾部, 与 Wings/Cape 同一管线) 里直接调
 * {@code EntityRenderDispatcher.render(player, ...)} 把完整模型补画上去:</p>
 * <ul>
 *   <li>相机在眼位 (y+1.62), 身体全部在 0.6 格以外, 近裁剪面 0.05 不会切到。</li>
 *   <li>藏头: {@code PlayerModel.head.visible = false} (帽子是头的子节点, 一起藏),
 *       否则相机嵌在头方块里, 边缘会有近裁剪碎面。渲染完恢复。</li>
 *   <li>名字牌: 自己的 nametag 会挂在头顶跟着画, EntityRendererPatch 里对
 *       第一人称的自己统一取消。</li>
 *   <li>光照: 直接调 {@code dispatcher.getPackedLightCoords} 拿原版光照值,
 *       SkinGlow 开着时自然是满亮 (两者同源)。</li>
 * </ul>
 *
 * <h2>已知边界</h2>
 * <p>披风/鞘翅/手持原版第一人称手臂照常渲染 (手臂可用 Hide Arms 关掉, 避免和身体
 * 模型的手臂重复); 俯视极端角度下肩部偶尔贴近近裁剪面, 属该类实现的固有形态
 * (FirstPerson Model mod 同样存在)。纯渲染层, 零发包, 服务端无感知。</p>
 */
public class FirstPersonModel extends Module {
    public static FirstPersonModel INSTANCE;

    /** 第一人称时不画原版的手臂 (默认关: 拿着武器时手+身体并存更接近 vanilla 观感)。 */
    public final BooleanSetting hideArms = new BooleanSetting("Hide Arms", false);
    /** 藏自己的头模型 (避免相机嵌入头部出现裁剪碎面; 基本必须开)。 */
    public final BooleanSetting hideHead = new BooleanSetting("Hide Head", true);

    public FirstPersonModel() {
        super("FirstPersonModel", Category.RENDER);
        INSTANCE = this;
    }

    /**
     * 第一人称补画自己时, 头部这一圈的"额外几何"要不要一起跳过。
     *
     * <p>只把 {@code PlayerModel.head/hat} 置灰是不够的: {@code EntityRenderDispatcher.render}
     * 会把 {@code PlayerRenderer} 的**整条 layer 链**一起走, 其中至少两处在头部生成立体:
     * {@code HumanoidArmorLayer} 的头盔、{@code CustomHeadLayer} 的头戴物 (南瓜/头颅)。
     * 它们的部件属于各自的 model 实例, 与 {@code PlayerModel.head.visible} 无关。</p>
     *
     * <p>相机就在眼位 (脚上 1.62 格), 正是头部立方体内部: 头皮肤藏掉了, 头盔还在, 于是整个
     * 视野被头盔内壁铺满 —— 表现就是"第一人称只能看见自己的头", 且调 Hide Head 完全没用。</p>
     */
    public static boolean skipOwnHeadLayers(LivingEntity entity) {
        FirstPersonModel fpm = INSTANCE;
        if (fpm == null || !fpm.isEnabled() || entity == null) {
            return false;
        }
        if (!fpm.hideHead.getValue()) {
            return false;
        }
        if (mc == null || mc.player == null || entity != mc.player) {
            return false;
        }
        return mc.options.getCameraType().isFirstPerson();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (!this.isEnabled() || mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson() || mc.player.isSpectator()) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (camera == null || dispatcher == null) {
            return;
        }
        if (!(dispatcher.getRenderer(mc.player) instanceof PlayerRenderer playerRenderer)) {
            return;
        }
        // PlayerRenderer 的 getModel() 静态类型已是 PlayerModel<AbstractClientPlayer>,
        // 再 instanceof PlayerModel<?> 会被判"表达式类型已是模式类型的子类型"而编译报错。
        PlayerModel<?> model = playerRenderer.getModel();
        if (model == null) {
            return;
        }

        float partialTick = event.partialTick();
        AbstractClientPlayer player = mc.player;

        Vec3 cameraPos = camera.getPosition();
        double x = Mth.lerp(partialTick, player.xOld, player.getX()) - cameraPos.x;
        double y = Mth.lerp(partialTick, player.yOld, player.getY()) - cameraPos.y;
        double z = Mth.lerp(partialTick, player.zOld, player.getZ()) - cameraPos.z;
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        int light = dispatcher.getPackedLightCoords(player, partialTick);

        PoseStack poseStack = event.poseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        boolean headVisible = model.head.visible;
        boolean hatVisible = model.hat.visible;
        if (this.hideHead.getValue()) {
            model.head.visible = false;
            model.hat.visible = false;
        }
        try {
            dispatcher.render(player, x, y, z, bodyYaw, partialTick, poseStack, buffer, light);
            // 实体批必须当场 flush: renderLevel 的统一 endBatch 在 RenderEvent 派发点之前,
            // 不冲掉的话这一批要等到下一帧透明物 pass 才上屏。
            buffer.endBatch();
        } finally {
            model.head.visible = headVisible;
            model.hat.visible = hatVisible;
        }
    }
}
