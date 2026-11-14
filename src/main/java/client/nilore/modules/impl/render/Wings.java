package client.nilore.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.render.ColorUtil;

/**
 * Cosmetic wings drawn behind the player.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Fully procedural — no texture asset is shipped, so the module cannot fail on a
 *       missing resource and stays tiny after the build-time class-name obfuscation.</li>
 *   <li>Third person only for the local player: in first person your own wings are simply
 *       not drawn ({@code CameraType#isFirstPerson}). Wings on other players are always
 *       drawn, since they are always seen from outside.</li>
 *   <li>Purely client side — nothing is sent to the server.</li>
 * </ul>
 *
 * <p>Coordinate convention of {@link RenderEvent}: the pose stack handed to us is the level
 * render view matrix with the camera already rotated into place but <b>not</b> translated,
 * so world space has to be shifted by {@code -cameraPosition} before emitting vertices.
 * This is the same convention {@code RenderUtil.drawBoxVerts} uses (it subtracts the camera
 * position itself). After the shift, {@code mulPose(Axis.YP.rotationDegrees(-bodyYaw))}
 * gives a frame where local {@code -Z} points behind the entity and local {@code +Y} up.
 */
public class Wings extends Module {
    /** Feather count per wing; enough to read as a wing without a per-frame vertex budget. */
    private static final int FEATHERS = 8;

    public final ModeSetting style = new ModeSetting("Style", "Angel", "Demon", "Blade");
    public final ModeSetting colorMode = new ModeSetting("Color", "Theme", "Custom", "Rainbow");

    public final NumberSetting red = new NumberSetting("Red", 150, 0, 255, 1,
            () -> this.colorMode.is("Custom"));
    public final NumberSetting green = new NumberSetting("Green", 200, 0, 255, 1,
            () -> this.colorMode.is("Custom"));
    public final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1,
            () -> this.colorMode.is("Custom"));
    public final NumberSetting rainbowSpeed = new NumberSetting("Rainbow Speed", 6, 2, 30, 1,
            () -> this.colorMode.is("Rainbow"));

    public final NumberSetting scale = new NumberSetting("Scale", 1.0, 0.4, 2.5, 0.05);
    public final NumberSetting spread = new NumberSetting("Spread", 1.0, 0.4, 2.0, 0.05);
    public final NumberSetting alpha = new NumberSetting("Alpha", 0.85, 0.1, 1.0, 0.05);
    public final NumberSetting backOffset = new NumberSetting("Back Offset", 0.12, 0.0, 0.5, 0.01);
    public final NumberSetting heightOffset = new NumberSetting("Height", 1.15, 0.3, 2.2, 0.05);

    public final BooleanSetting flap = new BooleanSetting("Flap", true);
    public final NumberSetting flapSpeed = new NumberSetting("Flap Speed", 3.0, 0.5, 12.0, 0.5,
            () -> this.flap.getValue());
    public final NumberSetting flapAmount = new NumberSetting("Flap Amount", 14.0, 0.0, 45.0, 1.0,
            () -> this.flap.getValue());

    public final BooleanSetting others = new BooleanSetting("Others", true);
    public final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false);

    public Wings() {
        super("Wings", Category.RENDER);
    }

    @Override
    public String getSuffix() {
        return this.style.getValue();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        // Requirement: your own wings are a third-person-only cosmetic. Other players are
        // always viewed from the outside, so the first-person gate only applies to self.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        PoseStack poseStack = event.poseStack();
        Vec3 cameraPos = camera.getPosition();
        int themeTint = this.resolveThemeColor();

        for (Player player : mc.level.players()) {
            if (player.isInvisible()) continue;
            if (player == mc.player) {
                if (firstPerson) continue;
            } else if (!this.others.getValue()) {
                continue;
            }
            this.renderWings(poseStack, player, event.partialTick(), cameraPos, themeTint);
        }
    }

    private void renderWings(PoseStack poseStack, Player player, float partialTick,
                             Vec3 cameraPos, int themeTint) {
        float scaleValue = this.scale.getValue().floatValue();
        float spreadValue = this.spread.getValue().floatValue();
        float alphaValue = this.alpha.getValue().floatValue();
        float height = this.heightOffset.getValue().floatValue();
        float back = this.backOffset.getValue().floatValue();

        double x = Mth.lerp(partialTick, player.xOld, player.getX());
        double y = Mth.lerp(partialTick, player.yOld, player.getY());
        double z = Mth.lerp(partialTick, player.zOld, player.getZ());
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);

        float flapDegrees = 0.0f;
        if (this.flap.getValue()) {
            double phase = (System.currentTimeMillis() % 100_000L) / 1000.0 * this.flapSpeed.getValue().doubleValue();
            flapDegrees = (float) Math.sin(phase) * this.flapAmount.getValue().floatValue();
        }

        poseStack.pushPose();
        poseStack.translate(x - cameraPos.x, y + height - cameraPos.y, z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        if (this.throughWalls.getValue()) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int side = -1; side <= 1; side += 2) {
            poseStack.pushPose();
            // Beating: rotate each wing around the backward axis so both sides rise together.
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * flapDegrees));
            this.emitWing(buffer, poseStack.last().pose(), side,
                    scaleValue, spreadValue, alphaValue, back, themeTint);
            poseStack.popPose();
        }

        BufferBuilder.RenderedBuffer rendered = buffer.end();
        if (rendered != null) {
            BufferUploader.drawWithShader(rendered);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private void emitWing(BufferBuilder buffer, Matrix4f matrix, int side,
                          float scaleValue, float spreadValue, float alphaValue,
                          float back, int themeTint) {
        int feathers;
        float reach;
        float droop;
        float thickness;
        switch (this.style.getValue()) {
            case "Demon" -> {
                feathers = 5;
                reach = 1.25f;
                droop = 0.35f;
                thickness = 0.06f;
            }
            case "Blade" -> {
                feathers = 3;
                reach = 1.45f;
                droop = 0.55f;
                thickness = 0.045f;
            }
            default -> {
                feathers = FEATHERS;
                reach = 1.05f;
                droop = 0.95f;
                thickness = 0.075f;
            }
        }

        for (int i = 0; i < feathers; i++) {
            float t = feathers == 1 ? 0.0f : i / (float) (feathers - 1);
            // Roots sit in a short row along the spine, tips fan outwards and upwards.
            float rootX = side * (0.05f + 0.02f * i) * scaleValue;
            float rootY = -0.06f * i * scaleValue;
            float tipX = side * (0.55f + reach * t) * spreadValue * scaleValue;
            float tipY = (-droop + 1.55f * t) * scaleValue;
            float tipZ = -(back + (0.06f + 0.20f * (float) Math.sin(t * Math.PI)) * scaleValue);

            float rootHalf = thickness * scaleValue;
            float tipHalf = thickness * 0.42f * scaleValue;

            int rootColor = this.colorAt(t * 0.35f, alphaValue, themeTint);
            int tipColor = this.colorAt(0.65f + t * 0.35f, alphaValue * 0.55f, themeTint);

            // Local -Z is behind the entity, so roots are pulled back by `back`.
            buffer.vertex(matrix, rootX, rootY + rootHalf, -back).color(rootColor).endVertex();
            buffer.vertex(matrix, rootX, rootY - rootHalf * 0.35f, -back).color(rootColor).endVertex();
            buffer.vertex(matrix, tipX, tipY - tipHalf, tipZ).color(tipColor).endVertex();
            buffer.vertex(matrix, tipX, tipY + tipHalf, tipZ).color(tipColor).endVertex();
        }
    }

    /** Interpolates the configured colour ramp and applies the alpha multiplier. */
    private int colorAt(float progress, float alphaScale, int themeTint) {
        int base;
        if (this.colorMode.is("Rainbow")) {
            int speed = this.rainbowSpeed.getValue().intValue();
            base = ColorUtil.getRainbowColor(speed, Math.round(progress * 90.0f)).getRGB();
        } else if (this.colorMode.is("Custom")) {
            base = ColorUtil.fromRGB(
                    this.red.getValue().intValue(),
                    this.green.getValue().intValue(),
                    this.blue.getValue().intValue());
        } else {
            base = themeTint;
        }
        return ColorUtil.withAlpha(base, alphaScale);
    }

    /** Theme colour of the module list HUD, so wings follow the client accent colour. */
    private int resolveThemeColor() {
        ModuleListHud hud = NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class);
        if (hud == null) {
            return 0xFF7CC8FF;
        }
        try {
            return hud.getThemeColor(0, 0.0f, 3);
        } catch (RuntimeException ignored) {
            return 0xFF7CC8FF;
        }
    }
}
