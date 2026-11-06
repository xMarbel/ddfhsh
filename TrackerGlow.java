package pl.playertracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Czerwony glowing (po stronie klienta, bez mixinów):
 *  - ustawia flagę "świecenia" w DataTrackerze encji gracza,
 *  - wrzuca gracza do lokalnego (tylko u Ciebie) teamu o kolorze czerwonym -> obrys jest czerwony.
 * Wywoływane co klatkę z TracerRenderer (można też podpiąć pod tick).
 */
public final class TrackerGlow {

    private TrackerGlow() {}

    private static final String TEAM = "pt_glow_red";
    private static final int GLOW_BIT = 0x40;

    private record Applied(String name, String prevTeam) {}

    private static final Map<UUID, Applied> applied = new HashMap<>();
    private static Object lastWorld;
    private static TrackedData<Byte> FLAGS;
    private static boolean searched;

    @SuppressWarnings("unchecked")
    private static TrackedData<Byte> flags() {
        if (searched) return FLAGS;
        searched = true;
        try {
            // Entity.FLAGS to jedyne statyczne TrackedData<Byte> w klasie Entity (działa też na nazwach intermediary)
            for (Field f : Entity.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !TrackedData.class.isAssignableFrom(f.getType())) continue;
                Type g = f.getGenericType();
                if (g instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                        && pt.getActualTypeArguments()[0] == Byte.class) {
                    f.setAccessible(true);
                    FLAGS = (TrackedData<Byte>) f.get(null);
                    break;
                }
            }
        } catch (Throwable ignored) { }
        return FLAGS;
    }

    private static Team ensureTeam(Scoreboard sb) {
        Team t = sb.getTeam(TEAM);
        if (t == null) t = sb.addTeam(TEAM);
        if (t.getColor() != Formatting.RED) t.setColor(Formatting.RED);
        return t;
    }

    private static void setGlowFlag(AbstractClientPlayerEntity p, boolean on) {
        TrackedData<Byte> fl = flags();
        if (fl == null) return;
        DataTracker dt = p.getDataTracker();
        byte b = dt.get(fl);
        byte nb = (byte) (on ? (b | GLOW_BIT) : (b & ~GLOW_BIT));
        if (nb != b) dt.set(fl, nb);
    }

    public static void update() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            applied.clear();
            lastWorld = null;
            return;
        }
        if (lastWorld != mc.world) {
            applied.clear();
            lastWorld = mc.world;
        }

        TrackerState.Config c = TrackerState.C;
        Scoreboard sb = mc.world.getScoreboard();
        Team team = null;
        Set<UUID> keep = new HashSet<>();

        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            String name = p.getGameProfile().getName();
            TrackerState.Entry e = c.tracked.get(name);
            boolean want = c.enabled && c.glow && e != null && e.glow;
            if (!want) continue;

            keep.add(p.getUuid());
            if (team == null) team = ensureTeam(sb);

            Team cur = sb.getScoreHolderTeam(name);
            if (cur != team) {
                applied.put(p.getUuid(), new Applied(name, cur != null ? cur.getName() : null));
                sb.addScoreHolderToTeam(name, team);
            } else {
                applied.putIfAbsent(p.getUuid(), new Applied(name, null));
            }
            setGlowFlag(p, true);
        }

        // przywróć wszystkich, którzy już nie powinni świecić
        Iterator<Map.Entry<UUID, Applied>> it = applied.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Applied> en = it.next();
            if (keep.contains(en.getKey())) continue;
            restore(mc, sb, en.getKey(), en.getValue());
            it.remove();
        }
    }

    private static void restore(MinecraftClient mc, Scoreboard sb, UUID id, Applied a) {
        try {
            Team t = sb.getTeam(TEAM);
            if (t != null && sb.getScoreHolderTeam(a.name()) == t) {
                sb.removeScoreHolderFromTeam(a.name(), t);
                if (a.prevTeam() != null) {
                    Team prev = sb.getTeam(a.prevTeam());
                    if (prev != null) sb.addScoreHolderToTeam(a.name(), prev);
                }
            }
            if (mc.world != null && mc.world.getPlayerByUuid(id) instanceof AbstractClientPlayerEntity p)
                setGlowFlag(p, false);
        } catch (Throwable ignored) { }
    }
}
