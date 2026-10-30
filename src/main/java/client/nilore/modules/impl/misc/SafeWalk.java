package client.nilore.modules.impl.misc;

import com.mojang.blaze3d.platform.InputConstants;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.event.EventTarget;

public class SafeWalk
extends Module {
    /** 判定"脚下踩空"时把玩家碰撞盒向内收多少格。越大 = 越早蹲。 */
    private final NumberSetting edgeDistance = new NumberSetting("Edge Distance", 0.3, 0.1, 0.9, 0.05);
    /**
     * Minimum time a sneak is held, in ticks, counted from the moment it starts (0 = release as
     * soon as the edge is clear).
     *
     * <p>The timer starts on the sneak, not on the release: once the module starts holding sneak
     * it keeps holding for at least this long, even if the edge disappears immediately, so the
     * state cannot flicker one tick on / one tick off.
     */
    private final NumberSetting minSneakHold = new NumberSetting("Min Sneak Hold", 0, 0, 40, 1);

    /** Ticks the current sneak has been held for; 0 means the module is not sneaking. */
    private int sneakTicks;

    public SafeWalk() {
        super("SafeWalk", Category.MISC);
    }

    public static boolean isOnBlockEdge(float inset) {
        return !mc.level.getCollisions(mc.player, mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inset, 0.0, -inset)).iterator().hasNext();
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (!motionEvent.isPost() || mc.player == null || mc.level == null) {
            return;
        }
        // 玩家自己按着 shift：原版本来就在蹲，交给它，别去改键状态。
        // （旧实现无条件 setDown(...)，会把玩家手动的蹲键也一起改掉。）
        if (isShiftPhysicallyDown()) {
            this.sneakTicks = 0;
            return;
        }
        boolean onEdge = mc.player.onGround()
                && SafeWalk.isOnBlockEdge(this.edgeDistance.getValue().floatValue());
        // Release needs both: the edge is gone, and the sneak has already served its minimum.
        if (!onEdge && this.sneakTicks >= this.minSneakHold.getValue().intValue()) {
            this.sneakTicks = 0;
            mc.options.keyShift.setDown(false);
            return;
        }
        mc.options.keyShift.setDown(true);
        this.sneakTicks++;
    }

    private static boolean isShiftPhysicallyDown() {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
    }

    @Override
    public void onDisable() {
        this.sneakTicks = 0;
        mc.options.keyShift.setDown(isShiftPhysicallyDown());
        super.onDisable();
    }
}
