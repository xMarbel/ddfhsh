package pl.playertracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class PlayerTrackerClient implements ClientModInitializer {

    public static KeyBinding openKey;   // J - otwiera GUI
    public static KeyBinding trackKey;  // H - sledz gracza na celowniku

    private final Map<String, Integer> last = new HashMap<>();
    private int ticks = 0;

    @Override
    public void onInitializeClient() {
        TrackerState.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.playertracker.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.playertracker"));
        trackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.playertracker.track", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.playertracker"));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(TracerRenderer::renderHud);
        WorldRenderEvents.LAST.register(TracerRenderer::render);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            last.clear();
            return;
        }
        while (openKey.wasPressed())
            if (mc.currentScreen == null) mc.setScreen(new TrackerScreen());
        while (trackKey.wasPressed())
            if (mc.currentScreen == null) toggleLooked(mc);

        if (++ticks % 10 == 0) alerts(mc);
    }

    /** Dodaje / usuwa gracza, w ktorego celujesz (tolerancja ok. 5 stopni, dziala na dystans). */
    private void toggleLooked(MinecraftClient mc) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1f);
        AbstractClientPlayerEntity best = null;
        double bestDot = Math.cos(Math.toRadians(5));
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            Vec3d to = p.getPos().add(0, p.getHeight() / 2.0, 0).subtract(eye).normalize();
            double d = look.dotProduct(to);
            if (d > bestDot) { bestDot = d; best = p; }
        }
        if (best == null) {
            mc.player.sendMessage(Text.literal("§eNie celujesz w zadnego gracza."), true);
            return;
        }
        String name = best.getGameProfile().getName();
        if (TrackerState.C.tracked.containsKey(name)) {
            TrackerState.remove(name);
            mc.player.sendMessage(Text.literal("§cUsunieto " + name), true);
        } else {
            TrackerState.Result r = TrackerState.tryAdd(name);
            mc.player.sendMessage(Text.literal((r.ok() ? "§a" : "§c") + r.msg()), true);
        }
    }

    /** Alerty: dolaczyl / wyszedl / w zasiegu - dla graczy z listy. */
    private void alerts(MinecraftClient mc) {
        TrackerState.Config c = TrackerState.C;
        last.keySet().removeIf(n -> !c.tracked.containsKey(n));
        for (String name : c.tracked.keySet()) {
            int now = TracerRenderer.find(mc, name) != null ? 2 : (TrackerState.findEntry(name) != null ? 1 : 0);
            Integer prev = last.put(name, now);
            if (prev == null || prev == now || !c.alerts) continue;
            if (prev == 0) mc.player.sendMessage(Text.literal("§a" + name + " dolaczyl do gry."), false);
            else if (now == 0) mc.player.sendMessage(Text.literal("§c" + name + " wyszedl z gry."), false);
            else if (now == 2) mc.player.sendMessage(Text.literal("§e" + name + " jest w zasiegu."), false);
        }
    }
}
