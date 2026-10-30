package client.nilore.modules.impl.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.math.FastMath;

/**
 * 帧率优化。
 *
 * <p>两条独立的路子：
 * <ul>
 *   <li><b>Reduce Blocks</b> — {@code LevelRenderer.renderChunkLayer} 每帧对每个可见 section
 *       都会取一次 {@code CompiledChunk}。超出设定水平距离的 section 直接拿到
 *       {@code CompiledChunk.UNCOMPILED}，{@code isEmpty(renderType)} 随即为真，整个 section 的
 *       顶点缓冲连接口都不走（见 {@code LevelRendererPatch#onRenderSectionChunk}）。它只影响
 *       渲染，不影响碰撞、实体、方块实体 tick —— 视觉上等于把地形渲染距离压到设定值。</li>
 *   <li><b>Fast Math</b> — 把朝向/弹道热路径上的 {@code Math.sin/cos} 换成
 *       {@link FastMath} 的查表实现（见 {@code RotationUtil#getDirection} 等）。</li>
 * </ul>
 *
 * <p>两者默认都开，但 Reduce Blocks 的默认距离（64 格）刻意保守：它是渲染距离，不是游戏距离。
 */
public class BetterFPS extends Module {
    public static BetterFPS INSTANCE;

    private final BooleanSetting reduceBlocks = new BooleanSetting("Reduce Blocks", true);
    private final NumberSetting blockDistance = new NumberSetting("Block Distance", 64, 16, 256, 4,
            () -> (Boolean) this.reduceBlocks.getValue());
    private final BooleanSetting verticalCull = new BooleanSetting("Vertical Cull", false);
    private final NumberSetting verticalRange = new NumberSetting("Vertical Range", 48, 16, 128, 4,
            () -> (Boolean) this.verticalCull.getValue());
    private final BooleanSetting fastMath = new BooleanSetting("Fast Math", true);

    public BetterFPS() {
        super("BetterFPS", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.syncFastMath();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.syncFastMath();
        super.onDisable();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.syncFastMath();
    }

    /** 模块被关掉、或 Fast Math 被取消勾选时，热路径必须立刻回到精确实现。 */
    private void syncFastMath() {
        FastMath.setEnabled(this.isEnabled() && (Boolean) this.fastMath.getValue());
    }

    /**
     * 该 section 是否应该跳过渲染。{@code origin} 是 section 原点（16 格对齐）。
     *
     * <p>水平距离与相机比，而不是与玩家比：第三人称/自由视角下相机可能离玩家很远，
     * 用玩家坐标会让屏幕里空一片。
     */
    public boolean shouldSkipSection(BlockPos origin) {
        if (!this.isEnabled() || origin == null || !(Boolean) this.reduceBlocks.getValue()) {
            return false;
        }
        if (mc == null || mc.gameRenderer == null || mc.level == null) {
            return false;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double dx = origin.getX() + 8.0 - camera.x;
        double dz = origin.getZ() + 8.0 - camera.z;
        double limit = this.blockDistance.getValue().doubleValue();
        if (dx * dx + dz * dz > limit * limit) {
            return true;
        }
        if ((Boolean) this.verticalCull.getValue()) {
            double dy = origin.getY() + 8.0 - camera.y;
            double vertical = this.verticalRange.getValue().doubleValue();
            return Math.abs(dy) > vertical;
        }
        return false;
    }

    @Override
    public String getSuffix() {
        if (!this.isEnabled() || !(Boolean) this.reduceBlocks.getValue()) {
            return null;
        }
        return Integer.toString(this.blockDistance.getValue().intValue());
    }
}
