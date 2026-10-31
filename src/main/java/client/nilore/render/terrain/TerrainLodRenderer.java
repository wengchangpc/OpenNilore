package client.nilore.render.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TerrainLodRenderer {
    private static final int NEAR_CELL = 2;
    private static final int FAR_CELL = 4;
    private static final double FAR_CELL_FACTOR = 1.5;
    private static final int BUILD_BUDGET = 3;
    private static final float[] FACE_SHADE = {0.55f, 1.0f, 0.68f, 0.82f, 0.72f, 0.9f};
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final Map<Long, LodSection> CACHE = new HashMap<>();
    private static final List<Long> CANDIDATES = new ArrayList<>();
    private static final Set<Long> LIVE = new HashSet<>();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static long candidateAnchor = Long.MIN_VALUE;
    private static ClientLevel candidateLevel;

    private TerrainLodRenderer() {
    }

    private static final class LodSection {
        final BlockPos origin;
        final VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        int cell;
        boolean built;
        boolean hasMesh;

        LodSection(BlockPos origin) {
            this.origin = origin.immutable();
        }
    }

    public static void reset() {
        for (LodSection section : CACHE.values()) {
            section.buffer.close();
        }
        CACHE.clear();
        CANDIDATES.clear();
        LIVE.clear();
        candidateAnchor = Long.MIN_VALUE;
        candidateLevel = null;
    }

    public static boolean shouldReplace(BlockPos origin, double distanceSq, double start) {
        LodSection section = CACHE.get(SectionPos.asLong(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4));
        return section != null && section.hasMesh && distanceSq > start * start;
    }

    public static void render(PoseStack poseStack, double start) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.level != candidateLevel) {
            reset();
            candidateLevel = mc.level;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;
        BlockPos playerPos = mc.player.blockPosition();
        long anchor = SectionPos.asLong(playerPos.getX() >> 4, playerPos.getY() >> 4, playerPos.getZ() >> 4);
        if (anchor != candidateAnchor) {
            candidateAnchor = anchor;
            refreshCandidates(mc, camX, camZ);
        }
        buildQueue(mc, camX, camY, camZ, start);
        draw(poseStack, camX, camY, camZ, start);
    }

    private static void refreshCandidates(Minecraft mc, double camX, double camZ) {
        CANDIDATES.clear();
        LIVE.clear();
        int radius = mc.options.renderDistance().get();
        ChunkPos center = new ChunkPos(mc.player.blockPosition());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                        .getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                ChunkPos pos = chunk.getPos();
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i].hasOnlyAir()) {
                        continue;
                    }
                    long key = SectionPos.asLong(pos.x, chunk.getSectionYFromSectionIndex(i), pos.z);
                    CANDIDATES.add(key);
                    LIVE.add(key);
                }
            }
        }
        CANDIDATES.sort(Comparator.comparingDouble(key -> {
            double cx = SectionPos.x(key) * 16 + 8 - camX;
            double cz = SectionPos.z(key) * 16 + 8 - camZ;
            return cx * cx + cz * cz;
        }));
        CACHE.entrySet().removeIf(entry -> {
            if (LIVE.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().buffer.close();
            return true;
        });
    }

    private static void buildQueue(Minecraft mc, double camX, double camY, double camZ, double start) {
        int built = 0;
        for (Long key : CANDIDATES) {
            if (built >= BUILD_BUDGET) {
                return;
            }
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            double dx = sx * 16 + 8 - camX;
            double dz = sz * 16 + 8 - camZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= start * start) {
                continue;
            }
            int cell = distanceSq <= start * start * FAR_CELL_FACTOR * FAR_CELL_FACTOR ? NEAR_CELL : FAR_CELL;
            LodSection section = CACHE.computeIfAbsent(key, ignored -> new LodSection(new BlockPos(sx * 16, sy * 16, sz * 16)));
            if (section.built && section.cell == cell) {
                continue;
            }
            if (build(mc, section, cell)) {
                section.cell = cell;
                section.built = true;
            }
            built++;
        }
    }

    private static boolean build(Minecraft mc, LodSection section, int cell) {
        section.hasMesh = false;
        BlockPos origin = section.origin;
        LevelChunk chunk = mc.level.getChunkSource()
                .getChunk(origin.getX() >> 4, origin.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return false;
        }
        int index = chunk.getSectionIndex(origin.getY());
        if (index < 0 || index >= chunk.getSections().length) {
            return false;
        }
        LevelChunkSection chunkSection = chunk.getSection(index);
        int side = 16 / cell;
        int[] colors = new int[side * side * side];
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    sampleCell(mc.level, chunkSection, cx, cy, cz, cell, origin, colors);
                }
            }
        }
        float light = sectionLight(mc.level, origin);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    int slot = (cy * side + cz) * side + cx;
                    int color = colors[slot];
                    if (color == 0) {
                        continue;
                    }
                    float x0 = cx * cell;
                    float y0 = cy * cell;
                    float z0 = cz * cell;
                    float x1 = x0 + cell;
                    float y1 = y0 + cell;
                    float z1 = z0 + cell;
                    for (Direction direction : DIRECTIONS) {
                        if (!faceVisible(mc.level, origin, colors, side, cell, cx, cy, cz, direction)) {
                            continue;
                        }
                        any |= emit(builder, x0, y0, z0, x1, y1, z1, direction, color, light);
                    }
                }
            }
        }
        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (!any) {
            rendered.release();
            return true;
        }
        section.buffer.bind();
        section.buffer.upload(rendered);
        VertexBuffer.unbind();
        section.hasMesh = true;
        return true;
    }

    private static void sampleCell(ClientLevel level, LevelChunkSection chunkSection, int cx, int cy, int cz,
                                   int cell, BlockPos origin, int[] colors) {
        int slot = (cy * (16 / cell) + cz) * (16 / cell) + cx;
        int fallback = 0;
        for (int y = cell - 1; y >= 0; y--) {
            for (int z = 0; z < cell; z++) {
                for (int x = 0; x < cell; x++) {
                    BlockState state = chunkSection.getBlockState(cx * cell + x, cy * cell + y, cz * cell + z);
                    if (state.isAir()) {
                        continue;
                    }
                    int rgb = state.getMapColor(level, CURSOR.set(origin.getX() + cx * cell + x,
                            origin.getY() + cy * cell + y, origin.getZ() + cz * cell + z)).col & 0xFFFFFF;
                    if (rgb == 0) {
                        rgb = 0x8B8B8B;
                    }
                    if (state.canOcclude()) {
                        colors[slot] = rgb;
                        return;
                    }
                    if (fallback == 0) {
                        fallback = rgb;
                    }
                }
            }
        }
        if (fallback != 0) {
            colors[slot] = fallback;
        }
    }

    private static boolean faceVisible(ClientLevel level, BlockPos origin, int[] colors, int side,
                                       int cell, int cx, int cy, int cz, Direction direction) {
        int nx = cx + direction.getStepX();
        int ny = cy + direction.getStepY();
        int nz = cz + direction.getStepZ();
        if (nx >= 0 && nx < side && ny >= 0 && ny < side && nz >= 0 && nz < side) {
            return colors[(ny * side + nz) * side + nx] == 0;
        }
        BlockState outside = level.getBlockState(CURSOR.set(
                origin.getX() + nx * cell, origin.getY() + ny * cell, origin.getZ() + nz * cell));
        return outside.isAir() || (!outside.canOcclude() && outside.getFluidState().isEmpty());
    }

    private static float sectionLight(ClientLevel level, BlockPos origin) {
        int top = Mth.clamp(origin.getY() + 15, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int bottom = Mth.clamp(origin.getY() + 1, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        CURSOR.set(origin.getX() + 8, top, origin.getZ() + 8);
        int sky = level.getBrightness(LightLayer.SKY, CURSOR);
        CURSOR.set(origin.getX() + 8, bottom, origin.getZ() + 8);
        int block = level.getBrightness(LightLayer.BLOCK, CURSOR);
        return 0.45f + 0.55f * (Math.max(sky, block) / 15.0f);
    }

    private static boolean emit(BufferBuilder builder, float x0, float y0, float z0, float x1, float y1, float z1,
                                Direction direction, int rgb, float light) {
        int color = shade(rgb, FACE_SHADE[direction.ordinal()] * light);
        switch (direction) {
            case UP -> {
                builder.vertex(x0, y1, z0).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
            }
            case DOWN -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x1, y0, z0).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
                builder.vertex(x0, y0, z1).color(color).endVertex();
            }
            case NORTH -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x0, y1, z0).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
                builder.vertex(x1, y0, z0).color(color).endVertex();
            }
            case SOUTH -> {
                builder.vertex(x0, y0, z1).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
            }
            case WEST -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x0, y0, z1).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
                builder.vertex(x0, y1, z0).color(color).endVertex();
            }
            case EAST -> {
                builder.vertex(x1, y0, z0).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static int shade(int rgb, float factor) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) ((rgb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static void draw(PoseStack poseStack, double camX, double camY, double camZ, double start) {
        if (CACHE.isEmpty()) {
            return;
        }
        Matrix4f base = new Matrix4f(poseStack.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        Matrix4f modelView = new Matrix4f();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        for (LodSection section : CACHE.values()) {
            if (!section.hasMesh) {
                continue;
            }
            double dx = section.origin.getX() + 8 - camX;
            double dz = section.origin.getZ() + 8 - camZ;
            if (dx * dx + dz * dz <= start * start) {
                continue;
            }
            modelView.set(base).translate((float) (section.origin.getX() - camX),
                    (float) (section.origin.getY() - camY),
                    (float) (section.origin.getZ() - camZ));
            section.buffer.bind();
            section.buffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
        }
        RenderSystem.enableCull();
    }
}
