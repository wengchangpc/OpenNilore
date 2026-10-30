package client.nilore.modules.impl.render.nametag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.impl.render.NameTags;
import client.nilore.modules.impl.world.Teams;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;
import client.nilore.render.GlyphMetrics;
import client.nilore.render.Paint;
import client.nilore.render.Renderer;
import client.nilore.utils.math.MathUtil;
import client.nilore.utils.math.Vector2f;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.ProjectionUtil;
import client.nilore.utils.render.RenderUtil;

public class NavenNameTag extends NameTagStyle {
    private static final int BACKDROP_COLOR = ColorUtil.fromARGB(0, 0, 0, 48);
    private static final int HEALTH_COLOR = ColorUtil.fromARGB(0, 0, 0, 96);
    private static final int TEXT_COLOR = 0xCCFFFFFF;

    private static final float PAD_X = 4.0f;
    private static final float PAD_Y = 2.0f;
    private static final float TEXT_OFFSET = -1.0f;
    private static final float HEAD_GAP = 2.0f;

    private final Map<Entity, Vector2f> entityPositions = new ConcurrentHashMap<>();
    private float[] blurRects = new float[16];
    private int blurCount;
    private Paint paint;
    private FontRenderer cachedFont;
    private float cachedRasterScale = -1f;

    public NavenNameTag() {
        super("Naven");
    }

    @Override
    public String getName() {
        return "Naven";
    }

    @Override
    public void onEnable() {
        this.entityPositions.clear();
    }

    @Override
    public void onDisable() {
        this.entityPositions.clear();
    }

    private void updatePositions(float partialTick) {
        this.entityPositions.clear();
        if (mc.level == null || mc.player == null) {
            return;
        }
        double rangeSq = Math.pow(NameTags.INSTANCE.distanceSetting.getValue().doubleValue(), 2.0);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !entity.isAlive()) continue;
            if (entity.distanceToSqr(mc.player) > rangeSq) continue;
            if (!(entity instanceof Player)) continue;
            if (entity.getName().getString().startsWith("CIT-")) continue;
            if (entity.isInvisible() && !NameTags.INSTANCE.showHealthSetting.getValue()) continue;
            if (NameTags.INSTANCE.showPingSetting.getValue() && Teams.isSameTeam(entity)) continue;
            double x = MathUtil.lerp(partialTick, entity.xo, entity.getX());
            double y = MathUtil.lerp(partialTick, entity.yo, entity.getY()) + entity.getBbHeight() + 0.5;
            double z = MathUtil.lerp(partialTick, entity.zo, entity.getZ());
            Vector2f screen = ProjectionUtil.project(x, y, z, partialTick);
            if (screen == null) continue;
            screen.setY(screen.getY() - 2.0f);
            this.entityPositions.put(entity, screen);
        }
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        try {
            this.updatePositions(renderEvent.partialTick());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (this.entityPositions.isEmpty() || mc.level == null) {
            return;
        }
        float scale = NameTags.INSTANCE.scaleSetting.getValue().floatValue();
        float rasterScale = Fonts.deviceScale(scale);
        if (this.cachedFont == null || rasterScale != this.cachedRasterScale) {
            this.cachedFont = FontPresets.pingfang(32.0f, rasterScale, true);
            this.cachedRasterScale = rasterScale;
        }
        FontRenderer font = this.cachedFont;
        this.blurCount = 0;
        if (this.paint == null) {
            this.paint = new Paint();
        }
        Renderer.renderConsumer(ctx -> {
            for (Map.Entry<Entity, Vector2f> entry : this.entityPositions.entrySet()) {
                if (!(entry.getKey() instanceof Player player)) continue;
                Vector2f screenPos = entry.getValue();
                screenPos.set(Math.round(screenPos.x), Math.round(screenPos.y));

                float health = Math.min(player.getHealth(), 20.0f);
                float ratio = Math.max(0.0f, Math.min(1.0f, health / player.getMaxHealth()));
                String healthText = String.valueOf(Math.round(health));
                if (player.getAbsorptionAmount() > 0.0f) {
                    healthText = healthText + "+" + Math.round(player.getAbsorptionAmount());
                }
                String text = player.getName().getString() + " | " + healthText + "HP";

                GlyphMetrics metrics = font.getMetrics();
                float lineHeight = metrics.getLineHeight();
                float boxWidth = font.getBounds(text).getWidth() + PAD_X * 2.0f;
                float boxHeight = PAD_Y * 2.0f + lineHeight;
                float originX = -boxWidth / 2.0f;
                float originY = -boxHeight - HEAD_GAP;

                ctx.save();
                ctx.translate(screenPos.x, screenPos.y);
                ctx.scale(scale, scale);
                this.addBlurRect(screenPos.x + originX * scale, screenPos.y + originY * scale,
                        boxWidth * scale, boxHeight * scale);

                this.paint.setColor(BACKDROP_COLOR);
                ctx.drawRectXYWH(originX, originY, boxWidth, boxHeight, this.paint);
                if (ratio < 1.0f) {
                    this.paint.setColor(HEALTH_COLOR);
                    ctx.drawRectXYWH(originX, originY, boxWidth * ratio, boxHeight, this.paint);
                }

                float textY = originY + TEXT_OFFSET - metrics.ascent() + 1.0f;
                this.paint.setColor(TEXT_COLOR);
                ctx.drawString(text, originX + PAD_X, textY, font, this.paint);
                ctx.restore();
            }
        });
        for (int i = 0; i < this.blurCount; i++) {
            int base = i * 4;
            RenderUtil.drawBlurredRect(event.guiGraphics().pose(), this.blurRects[base], this.blurRects[base + 1],
                    this.blurRects[base + 2], this.blurRects[base + 3], 0.0f, 2.0f, 0.5f, 0);
        }
    }

    private void addBlurRect(float x, float y, float width, float height) {
        int base = this.blurCount * 4;
        if (base + 4 > this.blurRects.length) {
            float[] grown = new float[this.blurRects.length * 2];
            System.arraycopy(this.blurRects, 0, grown, 0, this.blurRects.length);
            this.blurRects = grown;
        }
        this.blurRects[base] = x;
        this.blurRects[base + 1] = y;
        this.blurRects[base + 2] = width;
        this.blurRects[base + 3] = height;
        this.blurCount++;
    }

    @Override
    public void onPacket(PacketEvent packetEvent) {
    }
}
