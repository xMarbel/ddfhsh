package pl.playertracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.math.MathHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TrackerState {

    public static final int MAX = 8;

    public static final int[] COLORS = {
            0xFF3C3C, 0x50FF50, 0x465AFF, 0xFFEB3C, 0xAA3CFF, 0xFF961E, 0x1EDCDC, 0xFF6EC8};
    public static final String[] COLOR_NAMES = {
            "Czerwony", "Zielony", "Niebieski", "Żółty", "Fioletowy", "Pomarańczowy", "Turkusowy", "Różowy"};

    public static final String[] STYLE_NAMES = {"Linia + obrys", "Sama linia", "Sam obrys"};
    public static final String[] ORIGIN_NAMES = {"Celownik", "Dół ekranu"};
    public static final int[] MAX_DIST = {32, 64, 128, 256, 0};
    public static final int[] OPACITY = {25, 50, 75, 100};
    public static final String[] CORNER_NAMES = {"Lewy górny", "Prawy górny", "Lewy dolny", "Prawy dolny"};
    public static final String[] MODE_NAMES = {"Tylko obserwowani", "Wszyscy gracze"};

    public static class Entry {
        public int color;
        public boolean visible = true; // linie / obrys do tego gracza
        public boolean glow = true;    // czerwony glowing tego gracza
        public Entry() { }
        public Entry(int color) { this.color = color; }
    }

    public static class Config {
        public Map<String, Entry> tracked = new LinkedHashMap<>();
        public boolean enabled = true, throughWalls = true, rainbow = false, alerts = true;
        public boolean hud = true, hudHealth = true, hudDistance = true;
        public int thickness = 1, style = 1, origin = 0, maxDistIdx = 4, opacityIdx = 3, hudCorner = 0;
        public int defaultColor = 2; // kolor linii do graczy spoza listy (indeks w COLORS) - 2 = niebieski
        public int ver = 3;
        public int mode = 1; // 0 = tylko obserwowani, 1 = wszyscy gracze (obserwowani w swoich kolorach)
        public boolean glow = true;        // globalny włącznik czerwonego glowingu
        public boolean lowHp = true;       // tracer do każdego gracza z małą ilością serc
        public int lowHpHearts = 4;        // próg: mniej niż tyle serc
    }

    public record Result(boolean ok, String msg, int color) {}

    public static Config C = new Config();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("playertracker.json");
    }

    public static void save() {
        try { Files.writeString(file(), GSON.toJson(C)); } catch (Exception ignored) { }
    }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            String txt = Files.readString(file());
            Config c = GSON.fromJson(txt, Config.class);
            if (c == null) return;
            if (c.tracked == null) c.tracked = new LinkedHashMap<>();
            if (!txt.contains("\"ver\"")) { // stary plik: nowy, cienki niebieski wyglad
                c.style = 1; c.thickness = 1; c.defaultColor = 2; c.ver = 3;
            }
            C = c;
        } catch (Exception ignored) { }
    }

    /* ---------- kolory ---------- */

    public static int freeColor() {
        for (int i = 0; i < COLORS.length; i++) {
            final int idx = i;
            if (C.tracked.values().stream().noneMatch(e -> e.color == idx)) return i;
        }
        return 0;
    }

    public static void cycleColor(String name) {
        Entry me = C.tracked.get(name);
        if (me == null) return;
        for (int step = 1; step <= COLORS.length; step++) {
            int next = (me.color + step) % COLORS.length;
            boolean used = false;
            for (Map.Entry<String, Entry> o : C.tracked.entrySet())
                if (!o.getKey().equals(name) && o.getValue().color == next) used = true;
            if (!used) { me.color = next; return; }
        }
    }

    /** Kolor RGB danego gracza (uwzględnia tryb tęczowy). */
    public static int rgb(String name) {
        Entry e = C.tracked.get(name);
        if (e == null) return 0xFFFFFF;
        if (C.rainbow) {
            int idx = new ArrayList<>(C.tracked.keySet()).indexOf(name);
            float h = (((System.currentTimeMillis() % 4000L) / 4000f) + idx * 0.13f) % 1f;
            return MathHelper.hsvToRgb(h, 0.75f, 1f);
        }
        return COLORS[Math.floorMod(e.color, COLORS.length)];
    }

    /* ---------- lista ---------- */

    public static PlayerListEntry findEntry(String name) {
        ClientPlayNetworkHandler h = MinecraftClient.getInstance().getNetworkHandler();
        if (h == null || name == null || name.isBlank()) return null;
        for (PlayerListEntry e : h.getPlayerList())
            if (e.getProfile().getName().equalsIgnoreCase(name.trim())) return e;
        return null;
    }

    public static Result tryAdd(String typed) {
        typed = typed == null ? "" : typed.trim();
        if (typed.isEmpty()) return new Result(false, "Wpisz nick gracza.", 0xFFAA00);
        PlayerListEntry e = findEntry(typed);
        if (e == null) return new Result(false, "Gracz \"" + typed + "\" nie istnieje lub jest offline.", 0xFF5555);
        String real = e.getProfile().getName();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && real.equalsIgnoreCase(mc.player.getGameProfile().getName()))
            return new Result(false, "Nie możesz śledzić samego siebie.", 0xFF5555);
        if (C.tracked.containsKey(real)) return new Result(false, real + " jest już obserwowany.", 0xFFAA00);
        if (C.tracked.size() >= MAX) return new Result(false, "Limit obserwowanych: " + MAX + ".", 0xFF5555);
        int ci = freeColor();
        C.tracked.put(real, new Entry(ci));
        save();
        return new Result(true, "Dodano " + real + " (linia: " + COLOR_NAMES[ci] + ").", 0x55FF55);
    }

    public static void remove(String name) {
        C.tracked.remove(name);
        save();
    }
}
