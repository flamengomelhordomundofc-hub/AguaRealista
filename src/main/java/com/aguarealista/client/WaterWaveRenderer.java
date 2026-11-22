package com.aguarealista.client;

import com.aguarealista.AguaRealistaMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

@Mod.EventBusSubscriber(modid = AguaRealistaMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class WaterWaveRenderer {

    private static final int RING_SEGMENTS = 24;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        List<WaterWaveManager.WaterCell> cells = WaterWaveManager.getWaterCells();
        List<WaterWaveManager.Wave> waves = WaterWaveManager.getWaves();
        if (cells.isEmpty() && waves.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        float time = (level.getGameTime() + partialTick) * 0.085f;

        // Cor dos reflexos baseada na hora do dia
        long dayTime = level.getDayTime() % 24000L;
        float shimmerR = 0.85f;
        float shimmerG = 0.96f;
        float shimmerB = 1.0f;
        float shimmerStrength = 1.0f;

        if ((dayTime >= 11800 && dayTime <= 13600) || (dayTime >= 22400)) {
            // Por do sol / nascer do sol dourado
            shimmerR = 1.0f;
            shimmerG = 0.72f;
            shimmerB = 0.42f;
            shimmerStrength = 0.95f;
        } else if (dayTime > 13600 && dayTime < 22400) {
            // Noite (reflexo lunar prateado)
            shimmerR = 0.55f;
            shimmerG = 0.72f;
            shimmerB = 0.95f;
            shimmerStrength = 0.55f;
        }
        if (level.isRaining()) {
            shimmerStrength *= 0.45f;
        }

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 1. Renderiza superficie ondulada, brilhos de luz e espuma nas bordas
        for (int i = 0; i < cells.size(); i++) {
            WaterWaveManager.WaterCell cell = cells.get(i);
            renderWaterCell(buffer, matrix, cell, time, partialTick, shimmerR, shimmerG, shimmerB, shimmerStrength);
        }

        // 2. Renderiza aneis 3D das ondas criadas por jogadores, mobs, barcos e chuva
        for (int i = 0; i < waves.size(); i++) {
            WaterWaveManager.Wave wave = waves.get(i);
            renderDynamicWave(buffer, matrix, level, wave, partialTick);
        }

        tesselator.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderWaterCell(
            BufferBuilder buffer,
            Matrix4f matrix,
            WaterWaveManager.WaterCell cell,
            float time,
            float partialTick,
            float shimmerR,
            float shimmerG,
            float shimmerB,
            float shimmerStrength
    ) {
        float x0 = cell.x;
        float x1 = cell.x + 1.0f;
        float z0 = cell.z;
        float z1 = cell.z + 1.0f;
        float baseY = cell.surfaceY + 0.048f;

        float h00 = getAmbientWaveHeight(x0, z0, time) + WaterWaveManager.getWaveDisplacementAt(x0, z0, partialTick);
        float h10 = getAmbientWaveHeight(x1, z0, time) + WaterWaveManager.getWaveDisplacementAt(x1, z0, partialTick);
        float h11 = getAmbientWaveHeight(x1, z1, time) + WaterWaveManager.getWaveDisplacementAt(x1, z1, partialTick);
        float h01 = getAmbientWaveHeight(x0, z1, time) + WaterWaveManager.getWaveDisplacementAt(x0, z1, partialTick);

        // Malha ondulada com sombreado de crista e vale
        emitWaterVertex(buffer, matrix, x0, baseY + h00, z0, h00, shimmerR, shimmerG, shimmerB, shimmerStrength);
        emitWaterVertex(buffer, matrix, x1, baseY + h10, z0, h10, shimmerR, shimmerG, shimmerB, shimmerStrength);
        emitWaterVertex(buffer, matrix, x1, baseY + h11, z1, h11, shimmerR, shimmerG, shimmerB, shimmerStrength);
        emitWaterVertex(buffer, matrix, x0, baseY + h01, z1, h01, shimmerR, shimmerG, shimmerB, shimmerStrength);

        // Espuma animada nas bordas (onde a agua encontra blocos solidos)
        if (cell.shoreMask != 0) {
            float foamWave = 0.14f + 0.07f * (float) Math.sin(time * 1.6f + cell.x * 0.8f + cell.z * 0.8f);
            float foamY = baseY + 0.025f;
            float foamAlpha = 0.36f + 0.14f * (float) Math.sin(time * 2.1f + cell.x + cell.z);

            if ((cell.shoreMask & 1) != 0) { // Norte (-Z)
                drawFoamStrip(buffer, matrix, x0, z0, x1, z0, x1, z0 + foamWave, x0, z0 + foamWave, foamY, foamAlpha);
            }
            if ((cell.shoreMask & 2) != 0) { // Sul (+Z)
                drawFoamStrip(buffer, matrix, x0, z1, x1, z1, x1, z1 - foamWave, x0, z1 - foamWave, foamY, foamAlpha);
            }
            if ((cell.shoreMask & 4) != 0) { // Leste (+X)
                drawFoamStrip(buffer, matrix, x1, z0, x1, z1, x1 - foamWave, z1, x1 - foamWave, z0, foamY, foamAlpha);
            }
            if ((cell.shoreMask & 8) != 0) { // Oeste (-X)
                drawFoamStrip(buffer, matrix, x0, z0, x0, z1, x0 + foamWave, z1, x0 + foamWave, z0, foamY, foamAlpha);
            }
        }
    }

    private static void emitWaterVertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            float x,
            float y,
            float z,
            float height,
            float shimmerR,
            float shimmerG,
            float shimmerB,
            float shimmerStrength
    ) {
        // Cristas ficam luminosas/brilhantes, vales ficam azul-profundo
        float crest = Mth.clamp((height + 0.02f) * 8.5f, 0.0f, 1.0f);
        float r = Mth.lerp(crest, 0.08f, shimmerR);
        float g = Mth.lerp(crest, 0.28f, shimmerG);
        float b = Mth.lerp(crest, 0.52f, shimmerB);
        float a = Mth.lerp(crest, 0.07f, 0.26f * shimmerStrength + 0.08f);

        buffer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
    }

    private static void drawFoamStrip(
            BufferBuilder buffer,
            Matrix4f matrix,
            float ax, float az,
            float bx, float bz,
            float cx, float cz,
            float dx, float dz,
            float y,
            float alpha
    ) {
        buffer.vertex(matrix, ax, y + 0.01f, az).color(0.92f, 0.97f, 1.0f, alpha).endVertex();
        buffer.vertex(matrix, bx, y + 0.01f, bz).color(0.92f, 0.97f, 1.0f, alpha).endVertex();
        buffer.vertex(matrix, cx, y, cz).color(0.82f, 0.94f, 1.0f, 0.0f).endVertex();
        buffer.vertex(matrix, dx, y, dz).color(0.82f, 0.94f, 1.0f, 0.0f).endVertex();
    }

    private static void renderDynamicWave(
            BufferBuilder buffer,
            Matrix4f matrix,
            ClientLevel level,
            WaterWaveManager.Wave wave,
            float partialTick
    ) {
        float progress = wave.getProgress(partialTick);
        float fadeIn = Mth.clamp((wave.age + partialTick) / 3.0f, 0.0f, 1.0f);
        float fadeOut = (1.0f - progress) * (1.0f - progress);
        float alpha = fadeIn * fadeOut * (wave.foamRing ? 0.62f : 0.35f) * Mth.clamp(wave.strength, 0.25f, 1.3f);

        if (alpha <= 0.01f) {
            return;
        }

        float radius = wave.getInterpolatedRadius(partialTick);
        float halfWidth = (wave.foamRing ? 0.16f : 0.08f) * (1.0f + progress * 0.8f);
        float innerR = Math.max(0.02f, radius - halfWidth);
        float outerR = radius + halfWidth;

        float baseY = (float) wave.y + 0.055f;
        float crestHeight = (wave.foamRing ? 0.095f : 0.025f) * wave.strength * fadeOut;

        float crestR = wave.foamRing ? 0.94f : 0.78f;
        float crestG = wave.foamRing ? 0.98f : 0.93f;
        float crestB = 1.0f;

        for (int s = 0; s < RING_SEGMENTS; s++) {
            double a0 = (s / (double) RING_SEGMENTS) * Math.PI * 2.0;
            double a1 = ((s + 1) / (double) RING_SEGMENTS) * Math.PI * 2.0;

            float cos0 = (float) Math.cos(a0);
            float sin0 = (float) Math.sin(a0);
            float cos1 = (float) Math.cos(a1);
            float sin1 = (float) Math.sin(a1);

            double midX = wave.x + ((cos0 + cos1) * 0.5) * radius;
            double midZ = wave.z + ((sin0 + sin1) * 0.5) * radius;

            // Nao desenha a onda por cima de terra/blocos solidos
            if (!WaterWaveManager.isWaterAt(level, midX, wave.y, midZ)) {
                continue;
            }

            float inX0 = (float) wave.x + cos0 * innerR;
            float inZ0 = (float) wave.z + sin0 * innerR;
            float inX1 = (float) wave.x + cos1 * innerR;
            float inZ1 = (float) wave.z + sin1 * innerR;

            float midX0 = (float) wave.x + cos0 * radius;
            float midZ0 = (float) wave.z + sin0 * radius;
            float midX1 = (float) wave.x + cos1 * radius;
            float midZ1 = (float) wave.z + sin1 * radius;

            float outX0 = (float) wave.x + cos0 * outerR;
            float outZ0 = (float) wave.z + sin0 * outerR;
            float outX1 = (float) wave.x + cos1 * outerR;
            float outZ1 = (float) wave.z + sin1 * outerR;

            // Lado interno subindo ate a crista da onda
            buffer.vertex(matrix, inX0, baseY, inZ0).color(0.45f, 0.80f, 0.98f, 0.0f).endVertex();
            buffer.vertex(matrix, inX1, baseY, inZ1).color(0.45f, 0.80f, 0.98f, 0.0f).endVertex();
            buffer.vertex(matrix, midX1, baseY + crestHeight, midZ1).color(crestR, crestG, crestB, alpha).endVertex();
            buffer.vertex(matrix, midX0, baseY + crestHeight, midZ0).color(crestR, crestG, crestB, alpha).endVertex();

            // Lado externo descendo da crista de volta para a agua
            buffer.vertex(matrix, midX0, baseY + crestHeight, midZ0).color(crestR, crestG, crestB, alpha).endVertex();
            buffer.vertex(matrix, midX1, baseY + crestHeight, midZ1).color(crestR, crestG, crestB, alpha).endVertex();
            buffer.vertex(matrix, outX1, baseY, outZ1).color(0.50f, 0.85f, 1.0f, 0.0f).endVertex();
            buffer.vertex(matrix, outX0, baseY, outZ0).color(0.50f, 0.85f, 1.0f, 0.0f).endVertex();
        }
    }

    private static float getAmbientWaveHeight(double wx, double wz, float time) {
        double w1 = Math.sin(wx * 0.65 + time * 1.15) * 0.017;
        double w2 = Math.cos(wz * 0.75 + time * 0.95) * 0.014;
        double w3 = Math.sin((wx + wz) * 0.45 - time * 1.4) * 0.011;
        return (float) (w1 + w2 + w3);
    }
}
