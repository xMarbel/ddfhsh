package pl.playertracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TrackerScreen extends Screen {

    private enum Tab {
        PLAYERS("Gracze"), ONLINE("Online"), TRACERS("Tracery"), SETTINGS("Ustawienia");
        final String label;
        Tab(String l) { label = l; }
    }

    // kolory interfejsu (RGB)
    private static final int ACCENT = 0x00B7FF, GREEN = 0x3CDC64, RED = 0xFF4D4D, GRAY = 0x8A97A3, GOLD = 0xFFB020;

    private static final int PW = 360, PH_MAX = 332, ROW = 21;
    private static Tab lastTab = Tab.PLAYERS;
    private static String keep = "";

    private Tab tab = lastTab;
    private int px, py, ph;
    private TextFieldWidget field;
    private int fx, fy, fw;               // pozycja "bąbelka" pola tekstowego
    private int scroll = 0;
    private boolean dirty = false;
    private String msg = "";
    private int msgColor = 0xFFFFFF;
    private long msgTime = 0;

    // dane aktualnie widocznej listy (do rysowania)
    private List<String> rowNames = new ArrayList<>();
    private List<PlayerListEntry> rowEntries = new ArrayList<>();
    private int listY, visRows, totalRows;

    public TrackerScreen() { super(Text.literal("Player Tracker")); }

    /* ================= budowa ================= */

    @Override
    protected void init() {
        px = (width - PW) / 2;
        ph = Math.min(PH_MAX, height - 8);
        py = (height - ph) / 2;
        field = null;

        // zakładki
        int tw = (PW - 28) / Tab.values().length;
        int i = 0;
        for (Tab t : Tab.values()) {
            BubbleButton b = new BubbleButton(px + 14 + i * tw, py + 33, tw - 3, 18, Text.literal(t.label), ACCENT, () -> {
                tab = t; lastTab = t; scroll = 0; msg = ""; rebuild();
            });
            b.selected = (t == tab);
            addDrawableChild(b);
            i++;
        }

        addDrawableChild(new BubbleButton(px + 14, py + ph - 24, PW - 28, 18,
                Text.literal("Zamknij"), RED, this::close));

        switch (tab) {
            case PLAYERS -> initPlayers();
            case ONLINE -> initOnline();
            case TRACERS -> initTracers();
            case SETTINGS -> initSettings();
        }
    }

    private void rebuild() { clearAndInit(); }

    private TextFieldWidget makeField(int x, int y, int w, String placeholder) {
        fx = x; fy = y; fw = w;
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + 10, y + 5, w - 20, 10, Text.literal("Nick"));
        f.setDrawsBackground(false);
        f.setMaxLength(16);
        f.setPlaceholder(Text.literal(placeholder).formatted(Formatting.DARK_GRAY));
        f.setText(keep);
        f.setChangedListener(s -> {
            keep = s;
            if (tab == Tab.ONLINE) { scroll = 0; dirty = true; }
        });
        addDrawableChild(f);
        setInitialFocus(f);
        return f;
    }

    private static Tooltip tip(String s) { return Tooltip.of(Text.literal(s)); }

    /* ---- zakładka: Gracze ---- */

    private void initPlayers() {
        field = makeField(px + 14, py + 58, PW - 28 - 80, "Wpisz nick gracza...");
        addDrawableChild(new BubbleButton(px + PW - 14 - 74, py + 58, 74, 18,
                Text.literal("+ Dodaj"), GREEN, this::addTyped));

        List<String> all = new ArrayList<>(TrackerState.C.tracked.keySet());
        listY = py + 116;
        visRows = Math.max(1, (ph - 42 - 116) / ROW);
        totalRows = all.size();
        scroll = Math.max(0, Math.min(scroll, totalRows - visRows));
        rowNames = new ArrayList<>(all.subList(scroll, Math.min(all.size(), scroll + visRows)));
        rowEntries.clear();

        int right = px + PW - 22;
        for (int i = 0; i < rowNames.size(); i++) {
            final String n = rowNames.get(i);
            final TrackerState.Entry e = TrackerState.C.tracked.get(n);
            int y = listY + i * ROW;

            BubbleButton sw = new BubbleButton(px + 19, y + 4, 11, 11, Text.empty(), 0, () -> {
                TrackerState.cycleColor(n); TrackerState.save(); rebuild();
            });
            sw.solid = TrackerState.COLORS[Math.floorMod(e.color, TrackerState.COLORS.length)];
            sw.setTooltip(tip("Zmień kolor linii"));
            addDrawableChild(sw);

            BubbleButton lines = new BubbleButton(right - 68, y + 1, 20, 17,
                    Text.literal(e.visible ? "§aL" : "§7L"), e.visible ? GREEN : GRAY, () -> {
                e.visible = !e.visible; TrackerState.save(); rebuild();
            });
            lines.setTooltip(tip("Linie (tracer) do tego gracza: " + (e.visible ? "ON" : "OFF")));
            addDrawableChild(lines);

            BubbleButton glow = new BubbleButton(right - 44, y + 1, 20, 17,
                    Text.literal(e.glow ? "§cG" : "§7G"), e.glow ? RED : GRAY, () -> {
                e.glow = !e.glow; TrackerState.save(); rebuild();
            });
            glow.setTooltip(tip("Czerwony glowing tego gracza: " + (e.glow ? "ON" : "OFF")));
            addDrawableChild(glow);

            BubbleButton del = new BubbleButton(right - 20, y + 1, 20, 17,
                    Text.literal("X"), RED, () -> {
                TrackerState.remove(n);
                say("Usunięto " + n + ".", 0xFF5555);
                rebuild();
            });
            del.setTooltip(tip("Usuń z listy"));
            addDrawableChild(del);
        }
    }

    /* ---- zakładka: Online ---- */

    private List<PlayerListEntry> onlineList() {
        List<PlayerListEntry> out = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || mc.player == null) return out;
        String me = mc.player.getGameProfile().getName();
        String f = keep.trim().toLowerCase();
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            String n = e.getProfile().getName();
            if (n.equalsIgnoreCase(me)) continue;
            if (!f.isEmpty() && !n.toLowerCase().contains(f)) continue;
            out.add(e);
        }
        out.sort(Comparator.comparing(e -> e.getProfile().getName().toLowerCase()));
        return out;
    }

    private void initOnline() {
        field = makeField(px + 14, py + 58, PW - 28, "Szukaj wśród graczy online...");
        List<PlayerListEntry> all = onlineList();
        listY = py + 90;
        visRows = Math.max(1, (ph - 42 - 90) / ROW);
        totalRows = all.size();
        scroll = Math.max(0, Math.min(scroll, totalRows - visRows));
        rowEntries = new ArrayList<>(all.subList(scroll, Math.min(all.size(), scroll + visRows)));
        rowNames.clear();

        int right = px + PW - 22;
        for (int i = 0; i < rowEntries.size(); i++) {
            final String n = rowEntries.get(i).getProfile().getName();
            int y = listY + i * ROW;
            boolean tracked = TrackerState.C.tracked.containsKey(n);
            addDrawableChild(new BubbleButton(right - 66, y + 1, 66, 17,
                    Text.literal(tracked ? "§cUsuń" : "§a+ Dodaj"), tracked ? RED : GREEN, () -> {
                if (TrackerState.C.tracked.containsKey(n)) {
                    TrackerState.remove(n);
                    say("Usunięto " + n + ".", 0xFF5555);
                } else {
                    TrackerState.Result r = TrackerState.tryAdd(n);
                    say(r.msg(), r.color());
                }
                rebuild();
            }));
        }
    }

    /* ---- zakładka: Tracery ---- */

    private static String onoff(boolean b) { return b ? "§aON" : "§cOFF"; }

    private void grid(int row, int col, int span, String text, int accent, Runnable r) {
        int half = (PW - 28 - 6) / 2;
        int x = px + 14 + (col == 1 ? half + 6 : 0);
        int w = span == 2 ? PW - 28 : half;
        addDrawableChild(new BubbleButton(x, py + 58 + row * 26, w, 22, Text.literal(text), accent, r));
    }

    private void initTracers() {
        TrackerState.Config c = TrackerState.C;
        grid(0, 0, 1, "Tracery: " + onoff(c.enabled), c.enabled ? GREEN : RED, () -> { c.enabled = !c.enabled; saveRebuild(); });
        grid(0, 1, 1, "Przez ściany: " + onoff(c.throughWalls), ACCENT, () -> { c.throughWalls = !c.throughWalls; saveRebuild(); });
        grid(1, 0, 1, "Styl: §f" + TrackerState.STYLE_NAMES[c.style], ACCENT, () -> { c.style = (c.style + 1) % 3; saveRebuild(); });
        grid(1, 1, 1, "Start: §f" + TrackerState.ORIGIN_NAMES[c.origin], ACCENT, () -> { c.origin = (c.origin + 1) % 2; saveRebuild(); });
        grid(2, 0, 1, "Grubość: §f" + c.thickness, ACCENT, () -> { c.thickness = c.thickness >= 5 ? 1 : c.thickness + 1; saveRebuild(); });
        grid(2, 1, 1, "Przezroczystość: §f" + TrackerState.OPACITY[c.opacityIdx] + "%", ACCENT,
                () -> { c.opacityIdx = (c.opacityIdx + 1) % TrackerState.OPACITY.length; saveRebuild(); });
        int md = TrackerState.MAX_DIST[c.maxDistIdx];
        grid(3, 0, 1, "Maks. dystans: §f" + (md == 0 ? "bez limitu" : md + "m"), ACCENT,
                () -> { c.maxDistIdx = (c.maxDistIdx + 1) % TrackerState.MAX_DIST.length; saveRebuild(); });
        grid(3, 1, 1, "Tęczowe kolory: " + onoff(c.rainbow), GOLD, () -> { c.rainbow = !c.rainbow; saveRebuild(); });
        grid(4, 0, 2, "Tryb pająków: §f" + TrackerState.MODE_NAMES[c.mode], GREEN,
                () -> { c.mode = (c.mode + 1) % TrackerState.MODE_NAMES.length; saveRebuild(); });
        grid(5, 0, 1, "Tracer <" + c.lowHpHearts + " serc: " + onoff(c.lowHp), RED,
                () -> { c.lowHp = !c.lowHp; saveRebuild(); });
        grid(5, 1, 1, "Próg serc: §f" + c.lowHpHearts, RED,
                () -> { c.lowHpHearts = c.lowHpHearts >= 10 ? 1 : c.lowHpHearts + 1; saveRebuild(); });
        grid(6, 0, 2, "Czerwony glowing (obserwowani): " + onoff(c.glow), RED,
                () -> { c.glow = !c.glow; saveRebuild(); });
    }

    /* ---- zakładka: Ustawienia ---- */

    private void initSettings() {
        TrackerState.Config c = TrackerState.C;
        grid(0, 0, 1, "HUD: " + onoff(c.hud), ACCENT, () -> { c.hud = !c.hud; saveRebuild(); });
        grid(0, 1, 1, "Róg HUD: §f" + TrackerState.CORNER_NAMES[c.hudCorner], ACCENT,
                () -> { c.hudCorner = (c.hudCorner + 1) % 4; saveRebuild(); });
        grid(1, 0, 1, "HUD – HP: " + onoff(c.hudHealth), ACCENT, () -> { c.hudHealth = !c.hudHealth; saveRebuild(); });
        grid(1, 1, 1, "HUD – dystans: " + onoff(c.hudDistance), ACCENT, () -> { c.hudDistance = !c.hudDistance; saveRebuild(); });
        grid(2, 0, 2, "Alerty (dołączył / wyszedł / w zasięgu): " + onoff(c.alerts), GOLD,
                () -> { c.alerts = !c.alerts; saveRebuild(); });
        grid(3, 0, 2, "Kolor tracerów (inni gracze): §f" + TrackerState.COLOR_NAMES[Math.floorMod(c.defaultColor, TrackerState.COLORS.length)], ACCENT,
                () -> { c.defaultColor = (c.defaultColor + 1) % TrackerState.COLORS.length; saveRebuild(); });
        grid(4, 0, 1, "Reset ustawień", GRAY, () -> {
            TrackerState.Config n = new TrackerState.Config();
            n.tracked = c.tracked;
            TrackerState.C = n;
            say("Przywrócono domyślne ustawienia.", 0xFFAA00);
            saveRebuild();
        });
        grid(4, 1, 1, "Wyczyść listę", RED, () -> {
            c.tracked.clear();
            say("Wyczyszczono listę obserwowanych.", 0xFF5555);
            saveRebuild();
        });
    }

    private void saveRebuild() { TrackerState.save(); rebuild(); }

    /* ================= akcje ================= */

    private void say(String m, int color) { msg = m; msgColor = color; msgTime = Util.getMeasuringTimeMs(); }

    private void addTyped() {
        TrackerState.Result r = TrackerState.tryAdd(keep);
        say(r.msg(), r.color());
        if (r.ok()) keep = "";
        rebuild();
    }

    @Override
    public void tick() {
        if (dirty) { dirty = false; rebuild(); }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = field != null && field.isFocused();
        if (tab == Tab.PLAYERS && typing && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            addTyped();
            return true;
        }
        if (!typing && PlayerTrackerClient.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (tab == Tab.PLAYERS || tab == Tab.ONLINE) {
            int next = scroll + (vAmount < 0 ? 1 : -1);
            int max = Math.max(0, totalRows - visRows);
            next = Math.max(0, Math.min(next, max));
            if (next != scroll) { scroll = next; dirty = true; }
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean shouldPause() { return false; }

    /* ================= rysowanie ================= */

    /** Status gracza: offline / online (daleko) / widać (dystans + HP). */
    private String status(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        AbstractClientPlayerEntity p = TracerRenderer.find(mc, name);
        if (p != null && mc.player != null) {
            float hp = p.getHealth();
            String hpCol = hp < TrackerState.C.lowHpHearts * 2f ? "§6" : "§c";
            return "§a● widać §f" + (int) p.distanceTo(mc.player) + "m " + hpCol + (int) Math.ceil(hp) + "HP";
        }
        if (TrackerState.findEntry(name) != null) return "§e● online §8(daleko)";
        return "§c● offline";
    }

    private void scrollbar(DrawContext ctx) {
        if (totalRows <= visRows) return;
        int x = px + PW - 14;
        int trackH = visRows * ROW - 2;
        Bubble.rect(ctx, x, listY, 4, trackH, 2, 0x30FFFFFF);
        int thumbH = Math.max(14, trackH * visRows / totalRows);
        int thumbY = listY + (trackH - thumbH) * scroll / Math.max(1, totalRows - visRows);
        Bubble.rect(ctx, x, thumbY, 4, thumbH, 2, 0xFF000000 | ACCENT);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        MinecraftClient mc = MinecraftClient.getInstance();

        // panel-bąbel + cień
        Bubble.shadow(ctx, px, py, PW, ph, 16);
        Bubble.bordered(ctx, px, py, PW, ph, 16, 0xFF000000 | ACCENT, 0xF2111C28, 0xF20A111A);

        // nagłówek
        Bubble.rect(ctx, px + 6, py + 5, PW - 12, 25, 12, 0xFF14506E, 0xFF0C2A3C);
        String me = mc.player != null ? mc.player.getGameProfile().getName() : "?";
        if (mc.player != null && mc.getNetworkHandler() != null) {
            PlayerListEntry self = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (self != null) {
                Bubble.rect(ctx, px + 11, py + 8, 19, 19, 7, 0xFF000000 | ACCENT);
                PlayerSkinDrawer.draw(ctx, self.getSkinTextures(), px + 13, py + 10, 15);
            }
        }
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("PLAYER TRACKER").formatted(Formatting.AQUA, Formatting.BOLD), px + 36, py + 8, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal(me + "  §a● Online"), px + 36, py + 19, 0xAAB7C2);

        String pill = TrackerState.C.enabled ? "§aTRACERY ON" : "§cTRACERY OFF";
        String cnt = "§7Obserwowani: §f" + TrackerState.C.tracked.size() + "§8/§f" + TrackerState.MAX;
        ctx.drawTextWithShadow(textRenderer, Text.literal(pill), px + PW - 14 - textRenderer.getWidth(Text.literal(pill)), py + 8, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal(cnt), px + PW - 14 - textRenderer.getWidth(Text.literal(cnt)), py + 19, 0xFFFFFF);

        // pasek zakładek
        Bubble.rect(ctx, px + 8, py + 31, PW - 16, 22, 11, 0x50000000);

        // pole tekstowe jako bąbelek
        if (field != null) {
            int border = field.isFocused() ? 0xFF000000 | ACCENT : 0xFF2B3C4B;
            Bubble.bordered(ctx, fx, fy, fw, 18, 9, border, 0xFF0B1319, 0xFF0E1822);
        }

        switch (tab) {
            case PLAYERS -> drawPlayers(ctx, mx, my);
            case ONLINE -> drawOnline(ctx, mx, my);
            case TRACERS -> hint(ctx, py + 58 + 7 * 26 + 2,
                    "Klawisz H – śledź / przestań śledzić gracza na celowniku.",
                    "Gracze z małą ilością serc dostają tracer automatycznie (pulsuje).");
            case SETTINGS -> hint(ctx, py + 58 + 5 * 26 + 8,
                    "Alerty działają dla graczy z Twojej listy obserwowanych.",
                    "Lista i ustawienia zapisują się automatycznie.");
        }

        if (!msg.isEmpty() && Util.getMeasuringTimeMs() - msgTime < 6000)
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), px + PW / 2, py + ph - 38, msgColor | 0xFF000000);
    }

    private void hint(DrawContext ctx, int y, String a, String b) {
        if (y + 22 > py + ph - 28) return;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(a), px + PW / 2, y, 0x7F8C99);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(b), px + PW / 2, y + 11, 0x7F8C99);
    }

    private void drawPlayers(DrawContext ctx, int mx, int my) {
        // podgląd wpisywanego gracza: online? widać? HP?
        String typed = keep.trim();
        int y2 = py + 80;
        if (!typed.isEmpty()) {
            PlayerListEntry e = TrackerState.findEntry(typed);
            if (e != null) {
                String real = e.getProfile().getName();
                PlayerSkinDrawer.draw(ctx, e.getSkinTextures(), px + 14, y2, 16);
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§f" + real + "  " + status(real) + "  §8(Enter)"), px + 36, y2 + 4, 0xFFFFFF);
            } else {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + typed + "  §c● offline / nie istnieje"),
                        px + 14, y2 + 4, 0xFFFFFF);
            }
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Wpisz nick – zobaczysz czy jest online i czy go widać"),
                    px + 14, y2 + 4, 0x6F7C88);
        }

        ctx.drawTextWithShadow(textRenderer, Text.literal("OBSERWOWANI").formatted(Formatting.AQUA),
                px + 14, py + 102, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8L = linie   G = glow"),
                px + PW - 14 - textRenderer.getWidth("L = linie   G = glow"), py + 102, 0xFFFFFF);
        ctx.fill(px + 14, py + 113, px + PW - 14, py + 114, 0x40FFFFFF);

        int right = px + PW - 22;
        for (int i = 0; i < rowNames.size(); i++) {
            String n = rowNames.get(i);
            int y = listY + i * ROW;
            boolean hov = mx >= px + 14 && mx <= right && my >= y && my < y + ROW - 2;
            Bubble.rect(ctx, px + 14, y, right - (px + 14), ROW - 2, 8, hov ? 0x3CFFFFFF : 0x22FFFFFF);

            PlayerListEntry e = TrackerState.findEntry(n);
            if (e != null) PlayerSkinDrawer.draw(ctx, e.getSkinTextures(), px + 35, y + 2, 15);
            else Bubble.rect(ctx, px + 35, y + 2, 15, 15, 4, 0xFF2A3540);

            int col = 0xFF000000 | TrackerState.rgb(n);
            ctx.drawTextWithShadow(textRenderer, Text.literal(n), px + 54, y + 6, col);
            ctx.drawTextWithShadow(textRenderer, Text.literal(status(n)), px + 156, y + 6, 0xFFFFFF);
        }
        if (rowNames.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Brak obserwowanych graczy."),
                    px + PW / 2, listY + 10, 0x6F7C88);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Wpisz nick powyżej albo wybierz z zakładki Online."),
                    px + PW / 2, listY + 22, 0x56626D);
        }
        scrollbar(ctx);
    }

    private void drawOnline(DrawContext ctx, int mx, int my) {
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("GRACZE ONLINE §7(" + totalRows + ")").formatted(Formatting.AQUA), px + 14, py + 79, 0xFFFFFF);
        ctx.fill(px + 14, py + 88, px + PW - 14, py + 89, 0x40FFFFFF);

        MinecraftClient mc = MinecraftClient.getInstance();
        int right = px + PW - 22;
        for (int i = 0; i < rowEntries.size(); i++) {
            PlayerListEntry e = rowEntries.get(i);
            String n = e.getProfile().getName();
            int y = listY + i * ROW;
            boolean hov = mx >= px + 14 && mx <= right && my >= y && my < y + ROW - 2;
            Bubble.rect(ctx, px + 14, y, right - (px + 14), ROW - 2, 8, hov ? 0x3CFFFFFF : 0x22FFFFFF);
            PlayerSkinDrawer.draw(ctx, e.getSkinTextures(), px + 18, y + 2, 15);

            boolean tracked = TrackerState.C.tracked.containsKey(n);
            ctx.drawTextWithShadow(textRenderer, Text.literal(n), px + 38, y + 6,
                    tracked ? 0xFF000000 | TrackerState.rgb(n) : 0xFFFFFFFF);

            AbstractClientPlayerEntity p = TracerRenderer.find(mc, n);
            String info = (p != null && mc.player != null ? "§a● widać §f" + (int) p.distanceTo(mc.player) + "m  " : "§e● online  ")
                    + "§8" + e.getLatency() + "ms";
            ctx.drawTextWithShadow(textRenderer, Text.literal(info), px + 150, y + 6, 0xFFFFFF);
        }
        if (rowEntries.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Brak graczy do wyświetlenia."),
                    px + PW / 2, listY + 10, 0x6F7C88);
        scrollbar(ctx);
    }
}
