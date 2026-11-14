package client.nilore.modules.impl.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.ChatUtil;

/**
 * KillEffect —— 击杀反馈三件套: 死亡位置的本地粒子爆发 + 本地音效 + 屏幕中央弹字。
 *
 * <h2>触发</h2>
 * <p>{@code Player.attack} 的 TAIL 会派发 {@code EntityRemoveEvent(dead=true, target)}
 * (PlayerPatch —— 命名是历史遗留, 它其实是"我的一次攻击完成"事件)。手动攻击和
 * KillAura 都走 {@code MultiPlayerGameMode.attack → player.attack}, 所以两条路全覆盖;
 * 攻击完成后目标 isDeadOrDying() 即判定击杀。远程击杀 (箭/药水) 不经过本地
 * Player.attack, 不在本模块覆盖内。同一实体 10 拍内去重, 防止 KillAura 在尸体上
 * 追击造成连环触发。</p>
 *
 * <h2>反作弊</h2>
 * <ul>
 *   <li>粒子: {@code ClientLevel.addParticle} 只在本端生成渲染粒子, 不入协议,
 *       服务端零感知 (和原版"其他玩家的粒子"由服务端播不同 —— 那是服务端主动发包,
 *       我们这条路服务端根本不知道)。</li>
 *   <li>音效: {@code SoundManager.play(SimpleSoundInstance.forUI)} 纯本地播放。</li>
 *   <li>弹字: Render2D 自绘文本, 不进聊天、不发聊天包。</li>
 * </ul>
 */
public class KillEffect extends Module {
    public static KillEffect INSTANCE;

    /** 粒子风格。Totem = 图腾绿色爆发, 最有击杀感。 */
    public final ModeSetting particles = new ModeSetting("Particles", "Totem",
            "Totem", "Crit", "Flame", "Soul", "Poof", "Explosion", "Off");
    public final NumberSetting particleCount = new NumberSetting("Particle Count", 40, 5, 200, 5,
            () -> !this.particles.is("Off"));
    /** 粒子散布半径 (格)。 */
    public final NumberSetting spread = new NumberSetting("Spread", 1.2, 0.3, 6, 1,
            () -> !this.particles.is("Off"));

    public final ModeSetting sound = new ModeSetting("Sound", "Pling", "Levelup", "Pling", "Off");
    public final NumberSetting soundPitch = new NumberSetting("Sound Pitch", 1.4, 0.5, 2.0, 0.1,
            () -> !this.sound.is("Off"));

    /** 屏幕中央弹字: 缩放进入 + 上浮淡出。 */
    public final ModeSetting text = new ModeSetting("Text", "GG!", "GG!", "+1", "EZZ", "Off");
    public final NumberSetting textDuration = new NumberSetting("Text Duration", 1.2, 0.4, 3.0, 0.2,
            () -> !this.text.is("Off"));
    public final NumberSetting textSize = new NumberSetting("Text Size", 4, 1, 8, 1,
            () -> !this.text.is("Off"));

    /** 弹字记录。 */
    private final List<Popup> popups = new ArrayList<>();
    /** 10 拍内已触发过的实体 id (去重)。 */
    private final List<int[]> recentKills = new ArrayList<>();

    public KillEffect() {
        super("KillEffect", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public String getSuffix() {
        String suffix = this.particles.is("Off") ? null : this.particles.getValue();
        if (suffix == null && !this.text.is("Off")) {
            suffix = this.text.getValue();
        }
        return suffix;
    }

    /** Player.attack TAIL = 我的一次攻击完成; 目标已死 = 击杀。 */
    @EventTarget
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!event.dead() || mc.player == null || mc.level == null) {
            return;
        }
        Entity target = event.entity();
        if (!(target instanceof LivingEntity living) || !living.isDeadOrDying()) {
            return;
        }
        if (target == mc.player) {
            return;
        }
        for (int[] recent : this.recentKills) {
            if (recent[0] == target.getId()) {
                return;
            }
        }
        this.recentKills.add(new int[]{target.getId(), 20});

        double x = living.getX();
        double y = living.getBoundingBox().minY + living.getBbHeight() * 0.5;
        double z = living.getZ();

        this.spawnParticles(x, y, z);
        this.playSound();
        if (!this.text.is("Off")) {
            this.popups.add(new Popup(this.text.getValue(), System.currentTimeMillis()));
        }
        ChatUtil.print(this.text.getValue() + " — killed " + living.getName().getString());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.recentKills.removeIf(recent -> --recent[1] <= 0);
        // 顺便清过期的弹字, 免得 Render2D 每帧遍历陈旧记录。
        if (!this.popups.isEmpty()) {
            long now = System.currentTimeMillis();
            this.popups.removeIf(popup -> now - popup.startMillis() > this.durationMillis() + 400);
        }
    }

    private void spawnParticles(double x, double y, double z) {
        if (this.particles.is("Off") || mc.level == null) {
            return;
        }
        ParticleOptions options = switch (this.particles.getValue()) {
            case "Crit" -> ParticleTypes.CRIT;
            case "Flame" -> ParticleTypes.FLAME;
            case "Soul" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "Poof" -> ParticleTypes.POOF;
            case "Explosion" -> ParticleTypes.EXPLOSION;
            default -> ParticleTypes.TOTEM_OF_UNDYING;  // Totem
        };
        int count = this.particleCount.getValue().intValue();
        // Explosion 是一次性大粒子, 爆几十个没意义还掉帧。
        if (this.particles.is("Explosion")) {
            count = Math.min(count, 6);
        }
        double spreadValue = this.spread.getValue().doubleValue();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(options,
                    x + (random.nextDouble() - 0.5) * spreadValue,
                    y + (random.nextDouble() - 0.5) * spreadValue,
                    z + (random.nextDouble() - 0.5) * spreadValue,
                    random.nextDouble() - 0.5, random.nextDouble() * 0.7, random.nextDouble() - 0.5);
        }
    }

    private void playSound() {
        if (this.sound.is("Off") || mc.getSoundManager() == null) {
            return;
        }
        float pitch = this.soundPitch.getValue().floatValue();
        try {
            if (this.sound.is("Levelup")) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, pitch));
            } else {
                // NOTE_BLOCK_PLING 是 Holder<SoundEvent>, forUI 有对应重载。
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch));
            }
        } catch (RuntimeException ignored) {
            // 音效系统未就绪 (主菜单/切换资源包) —— 静默跳过, 不值得为一声 pling 崩渲染。
        }
    }

    private long durationMillis() {
        return (long) (this.textDuration.getValue().floatValue() * 1000.0f);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.popups.isEmpty() || mc.player == null) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        if (graphics == null) {
            return;
        }
        long duration = this.durationMillis();
        long now = System.currentTimeMillis();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int centerY = mc.getWindow().getGuiScaledHeight() / 3;
        int scale = Math.max(1, this.textSize.getValue().intValue());

        for (Popup popup : this.popups) {
            long age = now - popup.startMillis();
            if (age < 0 || age > duration + 400) {
                continue;
            }
            float progress = Math.min(1.0f, age / (float) duration);
            // 0.15s 内从 1.6 倍缩到 1.0 (砸下来的感觉), 之后轻微上浮, 末段 0.3 淡出。
            float scaleAnim = age < 150 ? 1.6f - 0.6f * (age / 150.0f) : 1.0f;
            float rise = progress * 18.0f;
            int alpha = 255;
            if (duration - age < 300) {
                alpha = (int) Math.max(0.0f, 255.0f * (duration - age) / 300.0f);
            }
            if (alpha <= 0) {
                continue;
            }
            int color = 0xFFFFFF | (alpha << 24);

            String label = popup.label();
            int textWidth = mc.font.width(label) * scale;
            graphics.pose().pushPose();
            graphics.pose().translate(centerX, centerY - rise, 0.0f);
            graphics.pose().scale(scaleAnim * scale, scaleAnim * scale, 1.0f);
            // 带阴影的粗描边: 大字号下没阴影会糊成一片。
            graphics.drawString(mc.font, Component.literal(label),
                    -textWidth / (2 * scale), -mc.font.lineHeight / 2, color);
            graphics.pose().popPose();
        }
    }

    private record Popup(String label, long startMillis) {
    }
}
