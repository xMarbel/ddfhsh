package pl.playertracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** Przycisk-bąbelek (pigułka). solid >= 0 => kolorowa kropka (próbnik koloru). */
public class BubbleButton extends ClickableWidget {

    public boolean selected = false;
    public int solid = -1;
    private final int accent;
    private final Runnable action;

    public BubbleButton(int x, int y, int w, int h, Text label, int accent, Runnable action) {
        super(x, y, w, h, label);
        this.accent = accent;
        this.action = action;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
        boolean hov = isHovered();
        int r = Math.min(width, height) / 2;

        if (solid >= 0) {
            int col = 0xFF000000 | solid;
            Bubble.rect(ctx, getX() - 1, getY() - 1, width + 2, height + 2, r + 1, hov ? 0xFFFFFFFF : 0x60FFFFFF);
            Bubble.rect(ctx, getX(), getY(), width, height, r, col);
            return;
        }

        int base = 0xFF152330;
        int acc = 0xFF000000 | accent;
        int fill = Bubble.lerp(base, acc, selected ? 0.70f : hov ? 0.38f : 0.18f);
        int border = Bubble.lerp(base, acc, (selected || hov) ? 1f : 0.55f);
        Bubble.bordered(ctx, getX(), getY(), width, height, r, border,
                Bubble.lerp(fill, 0xFFFFFFFF, 0.08f), fill);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawCenteredTextWithShadow(tr, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        action.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
