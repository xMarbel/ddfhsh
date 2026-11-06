package pl.playertracker;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TracerRenderer {

    private TracerRenderer() {}

    private static boolean errorShown = false;

    public static AbstractClientPlayerEntity find(MinecraftClient mc, String name) {
        if (mc.world == null) return null;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers())
            if (p.getGameProfile().getName().equalsIgnoreCase(name) && p != mc.player) return p;
        return null;
    }

    private static final class Target {
        final AbstractClientPlayerEntity player;
        final int rgb;
        Target(AbstractClientPlayerEntity p, int rgb) { this.player = p; this.rgb = rgb; }
    }

    /** Kolor pulsujący czerwony <-> biały dla graczy z małą ilością HP. */
    private static int lowHpColor() {
        float t = (float) ((Math.sin(System.currentTimeMillis() / 140.0) + 1.0) / 2.0);
        int gb = (int) (t * 190);
        return (255 << 16) | (gb << 8) | gb;
    }

    /* ---------------- linie w świecie ---------------- */

    public static void render(WorldRenderContext ctx) {
        try {
            TrackerGlow.update();
        } catch (Throwable t) {
            reportOnce("glow", t);
        }
        try {
            draw(ctx);
        } catch (Throwable t) {
            reportOnce("tracery", t);
        }
    }

    /** Nie wywala gry, ale raz pokazuje błąd na czacie i w logu - żeby było widać czemu coś nie działa. */
    private static void reportOnce(String where, Throwable t) {
        t.printStackTrace();
        if (errorShown) return;
        errorShown = true;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null)
                mc.player.sendMessage(Text.literal("§c[PlayerTracker] Błąd (" + where + "): " + t), false);
        } catch (Throwable ignored) { }
    }

    private static boolean viewAlreadyApplied() {
        try {
            return !RenderSystem.getModelViewStack().equals(new Matrix4f(), 1e-4f);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void draw(WorldRenderContext ctx) {
        TrackerState.Config c = TrackerState.C;
        if (!c.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        // 1) zbierz cele PRZED rozpoczęciem rysowania
        int limit = TrackerState.MAX_DIST[c.maxDistIdx];
        float lowThreshold = c.lowHpHearts * 2f;
        int lowColor = lowHpColor();
        List<Target> targets = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            String name = p.getGameProfile().getName();
            TrackerState.Entry e = c.tracked.get(name);

            boolean low = c.lowHp && p.getHealth() > 0f && p.getHealth() < lowThreshold;
            boolean show = e != null ? (e.visible || low) : (c.mode == 1 || low);
            if (!show) continue;
            if (limit > 0 && p.distanceTo(mc.player) > limit) continue;

            int rgb = e != null ? TrackerState.rgb(name)
                    : (low ? lowColor : TrackerState.COLORS[Math.floorMod(c.defaultColor, TrackerState.COLORS.length)]);
            targets.add(new Target(p, rgb));
        }
        if (targets.isEmpty()) return;

        float td = ctx.tickCounter().getTickDelta(true);
        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).normalize();
        Vec3d start = look.multiply(1.0);
        if (c.origin == 1) start = start.subtract(new Vec3d(camera.getVerticalPlane()).multiply(0.5));

        // WŁASNA macierz widoku (obrót kamery) - nie zależy od tego, co podaje WorldRenderContext
        // (w nowszych wersjach matrixStack z kontekstu bywa pusty/null i wtedy linie znikały).
        // W niektórych wersjach (1.21.2+) obrót kamery jest już w macierzy modelu/widoku renderera;
        // wtedy wierzchołki dajemy z macierzą jednostkową, inaczej obrót zostałby dodany drugi raz.
        Matrix4f mat = viewAlreadyApplied()
                ? new Matrix4f()
                : new Matrix4f().rotation(camera.getRotation().conjugate(new Quaternionf()));
        int alpha = (int) (255 * (TrackerState.OPACITY[c.opacityIdx] / 100f));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (c.throughWalls) RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        // 2) grube linie (płaskie czworokąty zwrócone do kamery)
        if (c.style != 2) {
            BufferBuilder q = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            for (Target t : targets) {
                Vec3d lerped = t.player.getLerpedPos(td);
                Vec3d end = lerped.add(0, t.player.getHeight() / 2.0, 0).subtract(cam);
                int r = (t.rgb >> 16) & 255, g = (t.rgb >> 8) & 255, b = t.rgb & 255;
                thickLine(q, mat, start, end, look, c.thickness, r, g, b, alpha);
            }
            BufferRenderer.drawWithGlobalProgram(q.end());
        }

        // 3) obrysy hitboxów
        if (c.style != 1) {
            RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
            BufferBuilder l = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            for (Target t : targets) {
                Vec3d lerped = t.player.getLerpedPos(td);
                Vec3d off = lerped.subtract(t.player.getPos());
                Box box = t.player.getBoundingBox().offset(off).offset(-cam.x, -cam.y, -cam.z);
                int r = (t.rgb >> 16) & 255, g = (t.rgb >> 8) & 255, b = t.rgb & 255;
                box(l, mat, box, r, g, b, alpha);
            }
            RenderSystem.lineWidth(Math.max(1f, c.thickness));
            BufferRenderer.drawWithGlobalProgram(l.end());
            RenderSystem.lineWidth(1f);
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** Linia o stałej grubości na ekranie (szerokość rośnie z dystansem). */
    private static void thickLine(BufferBuilder q, Matrix4f m, Vec3d a, Vec3d b, Vec3d look,
                                  int thickness, int r, int g, int bl, int al) {
        Vec3d dir = b.subtract(a);
        Vec3d perp = dir.crossProduct(look);
        if (perp.lengthSquared() < 1e-8) perp = new Vec3d(0, 1, 0).crossProduct(look);
        if (perp.lengthSquared() < 1e-8) perp = new Vec3d(1, 0, 0);
        perp = perp.normalize();

        double k = 0.0007 * thickness;
        double ha = k * Math.max(a.length(), 0.5);
        double hb = k * Math.max(b.length(), 0.5);

        Vec3d a1 = a.add(perp.multiply(ha)), a2 = a.subtract(perp.multiply(ha));
        Vec3d b1 = b.add(perp.multiply(hb)), b2 = b.subtract(perp.multiply(hb));

        q.vertex(m, (float) a1.x, (float) a1.y, (float) a1.z).color(r, g, bl, al);
        q.vertex(m, (float) a2.x, (float) a2.y, (float) a2.z).color(r, g, bl, al);
        q.vertex(m, (float) b2.x, (float) b2.y, (float) b2.z).color(r, g, bl, al);
        q.vertex(m, (float) b1.x, (float) b1.y, (float) b1.z).color(r, g, bl, al);
    }

    private static void box(BufferBuilder b, Matrix4f m, Box x, int r, int g, int bl, int al) {
        float x1 = (float) x.minX, y1 = (float) x.minY, z1 = (float) x.minZ;
        float x2 = (float) x.maxX, y2 = (float) x.maxY, z2 = (float) x.maxZ;
        float[][] e = {
                {x1,y1,z1,x2,y1,z1},{x2,y1,z1,x2,y1,z2},{x2,y1,z2,x1,y1,z2},{x1,y1,z2,x1,y1,z1},
                {x1,y2,z1,x2,y2,z1},{x2,y2,z1,x2,y2,z2},{x2,y2,z2,x1,y2,z2},{x1,y2,z2,x1,y2,z1},
                {x1,y1,z1,x1,y2,z1},{x2,y1,z1,x2,y2,z1},{x2,y1,z2,x2,y2,z2},{x1,y1,z2,x1,y2,z2}};
        for (float[] l : e) {
            b.vertex(m, l[0], l[1], l[2]).color(r, g, bl, al);
            b.vertex(m, l[3], l[4], l[5]).color(r, g, bl, al);
        }
    }

    /* ---------------- HUD ---------------- */

    public static void renderHud(DrawContext ctx, RenderTickCounter tick) {
        TrackerState.Config c = TrackerState.C;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!c.hud || c.tracked.isEmpty() || mc.player == null
                || mc.options.hudHidden || mc.currentScreen != null) return;

        TextRenderer tr = mc.textRenderer;
        List<Text> lines = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        lines.add(Text.literal("§b§lPLAYER TRACKER " + (c.enabled ? "§a●" : "§c●")));
        cols.add(0xFFFFFF);

        for (Map.Entry<String, TrackerState.Entry> en : c.tracked.entrySet()) {
            String name = en.getKey();
            AbstractClientPlayerEntity t = find(mc, name);
            StringBuilder sb = new StringBuilder("● " + name);
            if (!en.getValue().visible) sb.append(" §8(bez linii)");
            if (t != null) {
                if (c.hudDistance) sb.append("  §7").append((int) t.distanceTo(mc.player)).append("m");
                if (c.hudHealth) sb.append("  §c").append((int) Math.ceil(t.getHealth())).append("HP");
            } else if (TrackerState.findEntry(name) != null) sb.append("  §8poza zasięgiem");
            else sb.append("  §8offline");
            lines.add(Text.literal(sb.toString()));
            cols.add(TrackerState.rgb(name));
        }

        int w = 0;
        for (Text t : lines) w = Math.max(w, tr.getWidth(t));
        w += 18;
        int h = lines.size() * 10 + 10;
        boolean right = c.hudCorner == 1 || c.hudCorner == 3;
        boolean bottom = c.hudCorner >= 2;
        int x = right ? ctx.getScaledWindowWidth() - w - 4 : 4;
        int y = bottom ? ctx.getScaledWindowHeight() - h - 4 : 4;

        Bubble.shadow(ctx, x, y, w, h, 8);
        Bubble.bordered(ctx, x, y, w, h, 8, 0xFF00B7FF, 0xD0101B26, 0xD00A1118);
        for (int i = 0; i < lines.size(); i++)
            ctx.drawTextWithShadow(tr, lines.get(i), x + 9, y + 6 + i * 10, cols.get(i));
    }
}
