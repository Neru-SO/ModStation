package com.terraformersmc.modmenu.gui;

import com.google.common.base.Joiner;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.config.ModMenuConfigManager;
import com.terraformersmc.modmenu.gui.widget.ModListWidget;
import com.terraformersmc.modmenu.gui.widget.entries.IndependentEntry;
import com.terraformersmc.modmenu.gui.widget.entries.ModListEntry;
import com.terraformersmc.modmenu.util.DrawingUtil;
import com.terraformersmc.modmenu.util.ModMenuScreenTexts;
import com.terraformersmc.modmenu.util.TranslationUtil;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.terraformersmc.modmenu.util.mod.ModBadgeRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.SharedConstants;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.Urls;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ModsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu | ModsScreen");
    private final Screen previousScreen;
    private ModListEntry selected;
    private @Nullable Mod selectedMod;
    private int focusedIndex = 0;
    private double dragStartX = 0;
    private double dragOffset = 0;
    private boolean isDragging = false;
    private boolean detailOverlayOpen = false;
    private double overlayAnimProgress = 0.0;
    private boolean suppressSelectUpdate = false;
    private boolean keepFilterOptionsShown = false;
    private boolean filterOptionsShown = false;
    public final Set<String> showModChildren = new HashSet<>();

    private TextFieldWidget searchBox;
    private ModListWidget modList;
    private ClickableWidget sortingButton;
    private ClickableWidget librariesButton;
    private final Map<String, ModListEntry> cardEntries = new HashMap<>();
    private final List<Double> animX = new ArrayList<>();
    private final List<Double> animY = new ArrayList<>();
    private final List<Double> animScale = new ArrayList<>();

    private static final int STACK_GAP = 25;
    private static final int Y_DROP = 8;
    private int headerH = 48;
    private static final int MAX_VISIBLE = 7;

    public final Map<String, Boolean> modHasConfigScreen = new HashMap<>();
    public final Map<String, Throwable> modScreenErrors = new HashMap<>();

    private static final Text SEND_FEEDBACK_TEXT = Text.translatable("menu.sendFeedback");
    private static final Text REPORT_BUGS_TEXT = Text.translatable("menu.reportBugs");

    public ModsScreen(Screen previousScreen) {
        super(ModMenuScreenTexts.TITLE);
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        String savedModId = this.selectedMod != null ? this.selectedMod.getId() : null;
        boolean savedOverlay = this.detailOverlayOpen;

        this.suppressSelectUpdate = true;
        this.modList = new ModListWidget(this.client, this.width, this.height, 0,
                ModMenuConfig.COMPACT_LIST.getValue() ? 23 : 36, this.modList, this);
        this.cardEntries.clear();

        if (savedModId != null && savedOverlay) {
            Mod restored = ModMenu.MODS.get(savedModId);
            if (restored != null) {
                this.selectedMod = restored;
                List<Mod> displayed = this.getDisplayedMods();
                for (int i = 0; i < displayed.size(); i++) {
                    if (displayed.get(i).getId().equals(savedModId)) {
                        this.focusedIndex = i;
                        break;
                    }
                }
            }
        }

        int searchW = Math.min(200, Math.max(120, this.width / 5));
        this.searchBox = new TextFieldWidget(this.textRenderer, this.width - searchW - 56, 10, searchW, 20, this.searchBox, ModMenuScreenTexts.SEARCH);
        
        
        
        this.searchBox.setChangedListener(text -> {
            this.modList.filter(text, false);
            this.cardEntries.clear();
            this.focusedIndex = 0;
            syncAnimArrays();
        });

        Text sortingText = ModMenuConfig.SORTING.getButtonText();
        Text librariesText = ModMenuConfig.SHOW_LIBRARIES.getButtonText();
        int sortingWidth = this.textRenderer.getWidth(sortingText) + 28;
        int librariesWidth = this.textRenderer.getWidth(librariesText) + 28;
        this.sortingButton = ButtonWidget.builder(sortingText,
                button -> { ModMenuConfig.SORTING.cycleValue(this.client.isShiftPressed() ? -1 : 1); ModMenuConfigManager.save(); modList.reloadFilters(); button.setMessage(ModMenuConfig.SORTING.getButtonText()); button.setWidth(this.textRenderer.getWidth(ModMenuConfig.SORTING.getButtonText()) + 26); })
                .size(sortingWidth, 20).build();
        this.librariesButton = ButtonWidget.builder(librariesText,
                button -> { ModMenuConfig.SHOW_LIBRARIES.toggleValue(); ModMenuConfigManager.save(); modList.reloadFilters(); button.setMessage(ModMenuConfig.SHOW_LIBRARIES.getButtonText()); button.setWidth(this.textRenderer.getWidth(ModMenuConfig.SHOW_LIBRARIES.getButtonText()) + 26); })
                .size(librariesWidth, 20).build();

        modList.finalizeInit();
        this.suppressSelectUpdate = false;
        this.setFilterOptionsShown(this.keepFilterOptionsShown && this.filterOptionsShown);
        this.addSelectableChild(this.searchBox);
        this.setInitialFocus(this.searchBox);
        this.keepFilterOptionsShown = true;
        syncAnimArrays();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (this.detailOverlayOpen) {
            if (input.key() == 256 || input.key() == 257) { // escape or enter to close
                this.detailOverlayOpen = false;
                return true;
            }
            return super.keyPressed(input) || this.searchBox.keyPressed(input);
        }
        if (input.key() == 262 || input.key() == 264) { // right or down
            int total = getDisplayedMods().size();
            if (focusedIndex < total - 1) {
                focusedIndex++;
                this.detailOverlayOpen = false;
            }
            return true;
        }
        if (input.key() == 263 || input.key() == 265) { // left or up
            if (focusedIndex > 0) {
                focusedIndex--;
                this.detailOverlayOpen = false;
            }
            return true;
        }
        return super.keyPressed(input) || this.searchBox.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return this.searchBox.charTyped(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.detailOverlayOpen) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int total = getDisplayedMods().size();
        if (scrollY > 0 && focusedIndex > 0) {
            focusedIndex--;
            this.detailOverlayOpen = false;
            return true;
        }
        if (scrollY < 0 && focusedIndex < total - 1) {
            focusedIndex++;
            this.detailOverlayOpen = false;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void drawX(DrawContext ctx, int x, int y, int s, int color) {
        for (int i = 0; i < s; i++) {
            ctx.fill(x + i, y + i, x + i + 2, y + i + 1, color);
            ctx.fill(x + s - 2 - i, y + i, x + s - i, y + i + 1, color);
        }
    }

    private void fillRoundedRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        ctx.fill(x, y, x + w, y + h, color);
    }

    private void drawCard(DrawContext ctx, int mouseX, int mouseY, int index,
                         List<Mod> mods, int cardW, int cardH, int cx, int cy) {
        int W = this.width;
        int total = mods.size();
        double ax = animX.get(index);
        double ay = animY.get(index);
        double as = animScale.get(index);

        int cardX = (int) (cx + ax - cardW * as / 2);
        int cardY = (int) (cy + ay - cardH * as / 2);
        int cw = (int) (cardW * as);
        int ch = (int) (cardH * as);

        if (cardX + cw < 0 || cardX > W) return;

        Mod mod = mods.get(index);
        boolean isFocused = index == focusedIndex;

        if (isFocused) {
            fillRoundedRect(ctx, cardX - 2, cardY - 2, cw + 4, ch + 4, 6, 0xFF7C2D8C);
            fillRoundedRect(ctx, cardX, cardY, cw, ch, 6, 0xFF1E0E2E);
            ctx.fill(cardX, cardY, cardX + cw, cardY + 3, 0xFFA855F7);
        } else {
            double dist = Math.abs(index - focusedIndex);
            int bg = dist <= 2 ? 0xFF12121E : 0xFF06060A;
            fillRoundedRect(ctx, cardX, cardY, cw, ch, 6, bg);
            ctx.fill(cardX, cardY, cardX + cw, cardY + 1, 0xFF2A1A2A);
        }

        float iconSize = 32 * (float) as;
        if (iconSize > 8) {
            ModListEntry entry = this.cardEntries.computeIfAbsent(mod.getId(), id -> new IndependentEntry(mod, this.modList));
            Identifier iconTex = entry.getIconTexture();
            int iconX = cardX + 8;
            int iconY = cardY + (int) (16 * as);
            int actualIconSize = (int) iconSize;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, iconTex, iconX, iconY, 0.0F, 0.0F, actualIconSize, actualIconSize, actualIconSize, actualIconSize, 0xFFFFFFFF);
        }

        int textX = cardX + (int)(48 * as);
        int textY = cardY + (int)(14 * as);
        String name = mod.getTranslatedName();
        int maxNameW = cw - (int)(56 * as);
        if (this.textRenderer.getWidth(name) > maxNameW && maxNameW > 0) {
            String truncated = this.textRenderer.trimToWidth(name, Math.max(0, maxNameW - this.textRenderer.getWidth("...")));
            name = truncated + "...";
        }
        ctx.drawTextWithShadow(this.textRenderer, name, textX, textY, 0xFFF1F5F9);

        if (mod.isReal()) {
            ctx.drawTextWithShadow(this.textRenderer, mod.getPrefixedVersion(), textX, textY + (int)(12 * as), 0xFFA855F7);
        }

        if (isFocused && !ModMenuConfig.HIDE_BADGES.getValue()) {
            new ModBadgeRenderer(textX + this.textRenderer.getWidth(mod.getTranslatedName()) + 4, textY, cardX + cw, mod, this).draw(ctx, mouseX, mouseY);
        }

        String summary = mod.getSummary();
        if (!summary.isEmpty() && as > 0.75) {
            DrawingUtil.drawWrappedString(ctx, summary, cardX + 8, cardY + (int)(65 * as), cw - 16, 2, 0xFF9CA3AF);
        }

        if (isFocused) {
            String counter = (index + 1) + " / " + total;
            int counterW = this.textRenderer.getWidth(counter);
            ctx.drawTextWithShadow(this.textRenderer, counter, cx - counterW / 2, cardY - 14, 0xFFC084FC);

            Text countText = this.computeModCountText(true, false);
            int countW = this.textRenderer.getWidth(countText);
            ctx.drawTextWithShadow(this.textRenderer, countText.asOrderedText(), cx - countW / 2, cardY + ch + 6, 0xFF9CA3AF);
        }
    }

    private void syncAnimArrays() {
        int n = getDisplayedMods().size();
        while (animX.size() < n) { animX.add(0.0); animY.add(0.0); animScale.add(1.0); }
        while (animX.size() > n) { animX.removeLast(); animY.removeLast(); animScale.removeLast(); }
        for (int i = 0; i < n; i++) {
            double[] t = targetFor(i, focusedIndex);
            animX.set(i, t[0]); animY.set(i, t[1]); animScale.set(i, t[2]);
        }
    }

    private double[] targetFor(int index, int focused) {
        int dist = index - focused;
        double xOff = dist * STACK_GAP;
        double yOff = Math.abs(dist) * Y_DROP;
        double scale = 1.0 - Math.abs(dist) * 0.04;
        if (scale < 0.65) scale = 0.65;
        return new double[]{xOff, yOff, scale};
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        int W = this.width;
        int H = this.height;
        this.headerH = Math.max(36, H / 16);

        drawContext.fill(0, 0, W, H, 0xFF08080D);
        drawContext.fill(0, 0, W, headerH, 0xFF0F0F17);
        drawContext.fill(0, headerH - 1, W, headerH, 0xFF1A1A28);

        int headerCenterY = (headerH - 22) / 2;
        this.searchBox.setY(headerCenterY + 1);
        int closeBtnX = 14;
        int closeBtnY = headerCenterY;
        boolean chHover = mouseX >= closeBtnX && mouseX < closeBtnX + 22 && mouseY >= closeBtnY && mouseY < closeBtnY + 22;
        drawContext.fill(closeBtnX, closeBtnY, closeBtnX + 22, closeBtnY + 22, chHover ? 0xFF3A1A3A : 0xFF1A0A1A);
        drawX(drawContext, closeBtnX + 3, closeBtnY + 3, 16, 0xFFF1F5F9);

        int sbX = this.searchBox.getX() - 1;
        int sbY = this.searchBox.getY() - 1;
        int sbW = this.searchBox.getWidth() + 2;
        int sbH = this.searchBox.getHeight() + 2;
        fillRoundedRect(drawContext, sbX, sbY, sbW, sbH, 4, 0xFF1A1A24);
        drawContext.fill(sbX, sbY, sbX + sbW, sbY + 1, 0xFF7C2D8C);

        int fbX = sbX + sbW + 5;
        int fbY = sbY;
        boolean fbHover = mouseX >= fbX && mouseX < fbX + 22 && mouseY >= fbY && mouseY < fbY + 22;
        fillRoundedRect(drawContext, fbX, fbY, 22, 22, 4, fbHover ? 0xFF3A1A3A : 0xFF1A0A1A);
        drawContext.fill(fbX + 5, fbY + 7, fbX + 17, fbY + 8, 0xFFF1F5F9);
        drawContext.fill(fbX + 7, fbY + 10, fbX + 15, fbY + 11, 0xFFF1F5F9);
        drawContext.fill(fbX + 9, fbY + 13, fbX + 13, fbY + 14, 0xFFF1F5F9);

        super.render(drawContext, mouseX, mouseY, delta);

        // Manual search box text rendering
        String searchText = this.searchBox.getText();
        int sbX2 = this.searchBox.getX();
        int sbY2 = this.searchBox.getY();
        int sbW2 = this.searchBox.getWidth();
        if (searchText.isEmpty() && !this.searchBox.isFocused()) {
            drawContext.drawTextWithShadow(this.textRenderer, ModMenuScreenTexts.SEARCH, sbX2 + 4, sbY2 + 6, 0xFF6B7280);
        } else {
            int cursorPos = Math.min(this.searchBox.getCursor(), searchText.length());
            int textW = this.textRenderer.getWidth(searchText);
            int scrollOff = 0;
            if (textW > sbW2 - 8) {
                int cursorX = this.textRenderer.getWidth(searchText.substring(0, cursorPos));
                scrollOff = Math.max(0, cursorX - (sbW2 - 12));
                if (scrollOff > textW - (sbW2 - 8)) scrollOff = Math.max(0, textW - (sbW2 - 8));
            }
            drawContext.drawTextWithShadow(this.textRenderer, searchText, sbX2 + 4 - scrollOff, sbY2 + 6, 0xFFF1F5F9);
            if (this.searchBox.isFocused() && (System.currentTimeMillis() % 1000 < 500)) {
                int cursorDrawX = sbX2 + 4 - scrollOff + this.textRenderer.getWidth(searchText.substring(0, cursorPos));
                drawContext.fill(cursorDrawX, sbY2 + 4, cursorDrawX + 1, sbY2 + 16, 0xFFF1F5F9);
            }
        }

        List<Mod> mods = getDisplayedMods();
        int total = mods.size();
        // Dynamic card sizing based on available height
        int availH = H - headerH - 20;
        int cardH = Math.min(availH, Math.max(160, (int)(availH * 0.75)));
        int cardW = (int)(cardH * 0.88);
        int stackGap = Math.max(10, Math.min(STACK_GAP, cardW / 5));
        int yDrop = Math.max(3, Math.min(Y_DROP, cardH / 30));
        if (total == 0) {
            drawContext.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No mods found"), W / 2, H / 2, 0xFF7C2D8C);
            return;
        }

        if (focusedIndex >= total) focusedIndex = total - 1;
        if (focusedIndex < 0) focusedIndex = 0;

        // Lerp animation toward targets
        double lerpSpeed = 0.12;
        int cx = W / 2;
        int cy = (H + headerH) / 2;

        for (int i = 0; i < total; i++) {
            int dist = i - focusedIndex;
            double tx = dist * stackGap;
            double ty = Math.abs(dist) * yDrop;
            double ts = 1.0 - Math.abs(dist) * 0.04;
            if (ts < 0.6) ts = 0.6;
            if (isDragging) {
                double previewShift = (dragOffset - dragStartX) * 0.3;
                animX.set(i, tx + previewShift);
                animY.set(i, ty);
                animScale.set(i, ts);
            } else {
                animX.set(i, animX.get(i) + (tx - animX.get(i)) * lerpSpeed);
                animY.set(i, animY.get(i) + (ty - animY.get(i)) * lerpSpeed);
                animScale.set(i, animScale.get(i) + (ts - animScale.get(i)) * lerpSpeed);
            }
        }

        // Draw cards: farthest first (pass 0), then focused (pass 1)
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) {
                for (int d = MAX_VISIBLE / 2; d >= 1; d--) {
                    int left = focusedIndex - d;
                    int right = focusedIndex + d;
                    if (left >= 0) drawCard(drawContext, mouseX, mouseY, left, mods, cardW, cardH, cx, cy);
                    if (right < total) drawCard(drawContext, mouseX, mouseY, right, mods, cardW, cardH, cx, cy);
                }
            } else {
                drawCard(drawContext, mouseX, mouseY, focusedIndex, mods, cardW, cardH, cx, cy);
            }
        }

        // Detail overlay
        double animTarget = this.detailOverlayOpen ? 1.0 : 0.0;
        this.overlayAnimProgress += (animTarget - this.overlayAnimProgress) * 0.15;
        if ((this.detailOverlayOpen || this.overlayAnimProgress > 0.01) && this.selectedMod != null) {
            double p = this.overlayAnimProgress;
            int bgAlpha = (int)(0x88 * p);
            drawContext.fill(0, 0, W, H, (bgAlpha << 24) | 0x000000);
            int ca = Math.min(255, (int)(0xFF * p));
            int pa = Math.min(0xCC, (int)(0xCC * p));

            int pw = (int)(W * 0.65);
            int ph = (int)(H * 0.7);
            int px = (W - pw) / 2;
            int py = (H - ph) / 2;
            int oci = this.focusedIndex;
            List<Mod> omods = this.getDisplayedMods();
            double cardCenterX = cx, cardCenterY = cy;
            int cardCW = 0, cardCH = 0;
            if (oci >= 0 && oci < omods.size()) {
                double as = this.animScale.get(oci);
                int oCardH = Math.min(H - headerH, Math.max(160, (int)((H - headerH) * 0.75)));
                int oCardW = (int)(oCardH * 0.88);
                cardCW = (int)(oCardW * as);
                cardCH = (int)(oCardH * as);
                double ax = this.animX.get(oci);
                double ay = this.animY.get(oci);
                cardCenterX = cx + ax;
                cardCenterY = cy + ay;
            }
            double oCenterX = px + pw / 2.0;
            double oCenterY = py + ph / 2.0;
            int animCX = (int)(cardCenterX + (oCenterX - cardCenterX) * p);
            int animCY = (int)(cardCenterY + (oCenterY - cardCenterY) * p);
            int apw = Math.max(1, cardCW + (int)((pw - cardCW) * p));
            int aph = Math.max(1, cardCH + (int)((ph - cardCH) * p));
            int apx = animCX - apw / 2;
            int apy = animCY - aph / 2;

            fillRoundedRect(drawContext, apx - 1, apy - 1, apw + 2, aph + 2, 8, (ca << 24) | 0x7C2D8C);
            fillRoundedRect(drawContext, apx, apy, apw, aph, 8, (pa << 24) | 0x14141E);
            drawContext.fill(apx, apy, apx + apw, apy + 3, (ca << 24) | 0x7C2D8C);

            int closeX = apx + apw - 30;
            int closeY = apy + (int)(aph * 0.03);
            boolean ch = mouseX >= closeX && mouseX < closeX + 20 && mouseY >= closeY && mouseY < closeY + 20;
            fillRoundedRect(drawContext, closeX, closeY, 20, 20, 4, (ca << 24) | (ch ? 0x3A1A3A : 0x1A0A1A));
            drawX(drawContext, closeX + 2, closeY + 2, 16, (ca << 24) | 0xF1F5F9);

            ModListEntry entry = this.cardEntries.get(this.selectedMod.getId());
            int iconSize = (int)(aph * 0.08);
            if (iconSize < 32) iconSize = 32;
            if (iconSize > 64) iconSize = 64;
            int iconX = apx + (int)(apw * 0.03);
            int iconY = apy + (int)(aph * 0.06);
            if (entry != null) {
                Identifier tex = entry.getIconTexture();
                drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, tex, iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize, (ca << 24) | 0xFFFFFF);
            }
            int tx = iconX + iconSize + (int)(apw * 0.02);
            int titleY = apy + (int)(aph * 0.06) + 2;
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(this.selectedMod.getTranslatedName()), tx, titleY, (ca << 24) | 0xF1F5F9);
            if (this.selectedMod.isReal()) {
                drawContext.drawTextWithShadow(this.textRenderer, this.selectedMod.getPrefixedVersion(), tx, titleY + 12, (ca << 24) | 0xA855F7);
            }
            String authors = this.selectedMod.getAuthors().isEmpty() ? "" : Joiner.on(", ").join(this.selectedMod.getAuthors());
            if (!authors.isEmpty()) {
                drawContext.drawTextWithShadow(this.textRenderer, Text.literal(I18n.translate("modmenu.authorPrefix", authors)), tx, titleY + 24, (ca << 24) | 0xC084FC);
            }

            String desc = this.selectedMod.getDescription();
            int descY = iconY + iconSize + (int)(aph * 0.04);
            int descLines = Math.max(4, (int)(aph / 80));
            DrawingUtil.drawWrappedString(drawContext, desc, apx + (int)(apw * 0.03), descY, apw - (int)(apw * 0.06), descLines, (ca << 24) | 0xD1D5DB);

            boolean isMc = this.selectedMod.getId().equals("minecraft");
            Text wsText = isMc ? SEND_FEEDBACK_TEXT : ModMenuScreenTexts.WEBSITE;
            Text isText = isMc ? REPORT_BUGS_TEXT : ModMenuScreenTexts.ISSUES;
            int configW = this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE) + 26;
            int websiteW = this.textRenderer.getWidth(wsText) + 26;
            int issuesW = this.textRenderer.getWidth(isText) + 26;
            int btw = Math.max(Math.max(configW, Math.max(websiteW, issuesW)), Math.min(120, (apw - 48) / 3));
            int bth = 26;
            int bty = apy + aph - bth - (int)(aph * 0.04);
            int centerX = apx + apw / 2;
            int groupW = 3 * btw + 8;
            int leftX = centerX - groupW / 2;

            int b1x = leftX;
            boolean h1 = mouseX >= b1x && mouseX < b1x + btw && mouseY >= bty && mouseY < bty + bth;
            boolean a1 = getModHasConfigScreen(this.selectedMod.getId());
            int b2x = leftX + btw + 4;
            int midBtnCenter = b2x + btw / 2;
            int b1sw = Math.max(0, (int)(btw * p));
            int b1Center = midBtnCenter + (int)(((b1x + btw / 2) - midBtnCenter) * p);
            int b1sx = b1Center - b1sw / 2;
            fillRoundedRect(drawContext, b1sx, bty, b1sw, bth, 6, (ca << 24) | (a1 ? (h1 ? 0x3A1A3A : 0x1A0A1A) : 0x0A0A10));
            drawContext.fill(b1sx, bty, b1sx + b1sw, bty + 2, (ca << 24) | (a1 ? 0x7C2D8C : 0x3A1A3A));
            if (b1sw > 10) drawContext.drawTextWithShadow(this.textRenderer, ModMenuScreenTexts.CONFIGURE, b1sx + (b1sw - this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE)) / 2, bty + 8, (ca << 24) | (a1 ? 0xF1F5F9 : 0x5C1A5C));

            boolean h2 = mouseX >= b2x && mouseX < b2x + btw && mouseY >= bty && mouseY < bty + bth;
            boolean a2 = isMc || this.selectedMod.getWebsite() != null;
            int b2sw = Math.max(0, (int)(btw * p));
            int b2sx = midBtnCenter - b2sw / 2;
            fillRoundedRect(drawContext, b2sx, bty, b2sw, bth, 6, (ca << 24) | (a2 ? (h2 ? 0x3A1A3A : 0x1A0A1A) : 0x0A0A10));
            drawContext.fill(b2sx, bty, b2sx + b2sw, bty + 2, (ca << 24) | (a2 ? 0x7C2D8C : 0x3A1A3A));
            if (b2sw > 10) drawContext.drawTextWithShadow(this.textRenderer, isMc ? SEND_FEEDBACK_TEXT : ModMenuScreenTexts.WEBSITE, b2sx + (b2sw - this.textRenderer.getWidth(isMc ? SEND_FEEDBACK_TEXT : ModMenuScreenTexts.WEBSITE)) / 2, bty + 8, (ca << 24) | (a2 ? 0xF1F5F9 : 0x5C1A5C));

            int b3x = leftX + 2 * btw + 8;
            boolean h3 = mouseX >= b3x && mouseX < b3x + btw && mouseY >= bty && mouseY < bty + bth;
            boolean a3 = isMc || this.selectedMod.getIssueTracker() != null;
            int b3sw = Math.max(0, (int)(btw * p));
            int b3Center = midBtnCenter + (int)(((b3x + btw / 2) - midBtnCenter) * p);
            int b3sx = b3Center - b3sw / 2;
            fillRoundedRect(drawContext, b3sx, bty, b3sw, bth, 6, (ca << 24) | (a3 ? (h3 ? 0x3A1A3A : 0x1A0A1A) : 0x0A0A10));
            drawContext.fill(b3sx, bty, b3sx + b3sw, bty + 2, (ca << 24) | (a3 ? 0x7C2D8C : 0x3A1A3A));
            if (b3sw > 10) drawContext.drawTextWithShadow(this.textRenderer, isMc ? REPORT_BUGS_TEXT : ModMenuScreenTexts.ISSUES, b3sx + (b3sw - this.textRenderer.getWidth(isMc ? REPORT_BUGS_TEXT : ModMenuScreenTexts.ISSUES)) / 2, bty + 8, (ca << 24) | (a3 ? 0xF1F5F9 : 0x5C1A5C));
        }

        // Filter popup
        if (this.filterOptionsShown) {
            int sortW = this.sortingButton.getWidth();
            int libW = this.librariesButton.getWidth();
            int popupWidth = Math.max(320, sortW + libW + 30);
            int popupHeight = 94;
            int popupX = (this.width - popupWidth) / 2;
            int popupY = (this.height - popupHeight) / 2;

            drawContext.fill(0, 0, this.width, this.height, 0x66000000);
            fillRoundedRect(drawContext, popupX - 1, popupY - 1, popupWidth + 2, popupHeight + 2, 8, 0xFF7C2D8C);
            fillRoundedRect(drawContext, popupX, popupY, popupWidth, popupHeight, 8, 0xCC14141E);
            drawContext.fill(popupX, popupY, popupX + popupWidth, popupY + 3, 0xFF7C2D8C);

            drawContext.drawTextWithShadow(this.textRenderer, Text.literal("Filter Options"), popupX + 12, popupY + 10, 0xFFF1F5F9);

            int spx = popupX + 12;
            int spy = popupY + 26;
            boolean sHover = mouseX >= spx && mouseX < spx + this.sortingButton.getWidth() && mouseY >= spy && mouseY < spy + 20;
            fillRoundedRect(drawContext, spx, spy, this.sortingButton.getWidth(), 20, 4, sHover ? 0xFF3A1A3A : 0xFF1A0A1A);
            drawContext.fill(spx, spy, spx + this.sortingButton.getWidth(), spy + 1, 0xFF7C2D8C);
            drawContext.drawTextWithShadow(this.textRenderer, ModMenuConfig.SORTING.getButtonText(),
                    spx + (this.sortingButton.getWidth() - this.textRenderer.getWidth(ModMenuConfig.SORTING.getButtonText())) / 2,
                    spy + 6, 0xFFF1F5F9);

            int lx = spx + this.sortingButton.getWidth() + 6;
            boolean lHover = mouseX >= lx && mouseX < lx + this.librariesButton.getWidth() && mouseY >= spy && mouseY < spy + 20;
            fillRoundedRect(drawContext, lx, spy, this.librariesButton.getWidth(), 20, 4, lHover ? 0xFF3A1A3A : 0xFF1A0A1A);
            drawContext.fill(lx, spy, lx + this.librariesButton.getWidth(), spy + 1, 0xFF7C2D8C);
            drawContext.drawTextWithShadow(this.textRenderer, ModMenuConfig.SHOW_LIBRARIES.getButtonText(),
                    lx + (this.librariesButton.getWidth() - this.textRenderer.getWidth(ModMenuConfig.SHOW_LIBRARIES.getButtonText())) / 2,
                    spy + 6, 0xFFF1F5F9);

            drawContext.drawTextWithShadow(this.textRenderer, Text.literal("ModStation Options"), popupX + 12, popupY + 52, 0xFFA855F7);

            int cfgBX = popupX + 12;
            int cfgBY = popupY + 66;
            int cfgBW = this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE) + 26;
            boolean cfgHover = mouseX >= cfgBX && mouseX < cfgBX + cfgBW && mouseY >= cfgBY && mouseY < cfgBY + 20;
            fillRoundedRect(drawContext, cfgBX, cfgBY, cfgBW, 20, 4, cfgHover ? 0xFF3A1A3A : 0xFF1A0A1A);
            drawContext.fill(cfgBX, cfgBY, cfgBX + cfgBW, cfgBY + 1, 0xFF7C2D8C);
            drawContext.drawTextWithShadow(this.textRenderer, ModMenuScreenTexts.CONFIGURE,
                    cfgBX + (cfgBW - this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE)) / 2,
                    cfgBY + 6, 0xFFF1F5F9);

            int clX = popupX + popupWidth - 22;
            int clY = popupY + 6;
            boolean clH = mouseX >= clX && mouseX < clX + 16 && mouseY >= clY && mouseY < clY + 16;
            fillRoundedRect(drawContext, clX, clY, 16, 16, 4, clH ? 0xFF3A1A3A : 0xFF1A0A1A);
            drawX(drawContext, clX + 2, clY + 2, 12, 0xFFF1F5F9);
        }
    }

    private Text computeModCountText(boolean includeLibs, boolean onInit) {
        int[] rootMods = formatModCount(ModMenu.ROOT_MODS.values()
                .stream()
                .filter(mod -> !mod.isHidden() && !mod.getBadges().contains(Mod.Badge.LIBRARY))
                .map(Mod::getId)
                .collect(Collectors.toSet()), onInit);
        if (includeLibs && !onInit) {
            int[] rootLibs = formatModCount(ModMenu.ROOT_MODS.values()
                    .stream()
                    .filter(mod -> !mod.isHidden() && mod.getBadges().contains(Mod.Badge.LIBRARY))
                    .map(Mod::getId)
                    .collect(Collectors.toSet()), false);

            if (rootLibs.length == 1 && rootLibs[0] != 0 || rootLibs.length == 2 && rootLibs[1] != 0) {
                return TranslationUtil.translateNumeric("modmenu.showingModsLibraries", rootMods, rootLibs);
            }
        }

        return TranslationUtil.translateNumeric("modmenu.showingMods", rootMods);
    }

    private Text computeLibraryCountText(boolean onInit) {
        if (!onInit) {
            int[] rootLibs = formatModCount(ModMenu.ROOT_MODS.values()
                    .stream()
                    .filter(mod -> !mod.isHidden() && mod.getBadges().contains(Mod.Badge.LIBRARY))
                    .map(Mod::getId)
                    .collect(Collectors.toSet()), false);
            return TranslationUtil.translateNumeric("modmenu.showingLibraries", rootLibs);
        } else {
            return Text.empty();
        }
    }

    private int[] formatModCount(Set<String> set, boolean allVisible) {
        int visible = this.modList.getDisplayedCountFor(set);
        int total = set.size();
        if (visible == total || allVisible) {
            return new int[]{total};
        } else {
            return new int[]{visible, total};
        }
    }

    private List<Mod> getDisplayedMods() {
        return this.modList.children().stream().map(ModListEntry::getMod).collect(Collectors.toList());
    }

    public void renderBackground(DrawContext drawContext, int mouseX, int mouseY, float delta) {
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        return handleMouseClick(click, doubleClick);
    }

    private boolean handleMouseClick(Click click, boolean doubleClick) {
        double mx = click.x();
        double my = click.y();
        int button = click.button();
        int W = this.width;
        int H = this.height;
        this.headerH = Math.max(36, H / 16);
        int headerCenterY = (this.headerH - 22) / 2;

        // Search box click - handle first so it doesn't trigger card clicks
        int sbX = this.searchBox.getX() - 1;
        int sbW = this.searchBox.getWidth() + 2;
        int sbH = 22;
        if (mx >= sbX && mx < sbX + sbW + 2 && my >= headerCenterY && my < headerCenterY + sbH) {
            if (button == 0) { this.searchBox.setFocused(true); } else { this.searchBox.setFocused(false); }
            return true;
        }
        this.searchBox.setFocused(false);

        int closeBtnX = 14;
        if (mx >= closeBtnX && mx < closeBtnX + 22 && my >= headerCenterY && my < headerCenterY + 22) {
            this.close();
            return true;
        }

        if (this.detailOverlayOpen && this.selectedMod != null) {
            double p = this.overlayAnimProgress;
            int pw = (int)(W * 0.65);
            int ph = (int)(H * 0.7);
            int px = (W - pw) / 2;
            int py = (H - ph) / 2;
            int oci = this.focusedIndex;
            List<Mod> omods = this.getDisplayedMods();
            double cardCenterX = W / 2.0, cardCenterY = (H + headerH) / 2.0;
            int cardCW = 0, cardCH = 0;
            if (oci >= 0 && oci < omods.size()) {
                double as = this.animScale.get(oci);
                int oCardH = Math.min(H - headerH, Math.max(160, (int)((H - headerH) * 0.75)));
                int oCardW = (int)(oCardH * 0.88);
                cardCW = (int)(oCardW * as);
                cardCH = (int)(oCardH * as);
                double ax = this.animX.get(oci);
                double ay = this.animY.get(oci);
                cardCenterX = W / 2.0 + ax;
                cardCenterY = (H + headerH) / 2.0 + ay;
            }
            double oCenterX = px + pw / 2.0;
            double oCenterY = py + ph / 2.0;
            int animCX = (int)(cardCenterX + (oCenterX - cardCenterX) * p);
            int animCY = (int)(cardCenterY + (oCenterY - cardCenterY) * p);
            int apw = Math.max(1, cardCW + (int)((pw - cardCW) * p));
            int aph = Math.max(1, cardCH + (int)((ph - cardCH) * p));
            int apx = animCX - apw / 2;
            int apy = animCY - aph / 2;

            int closeX = apx + apw - 30;
            int closeY = apy + (int)(aph * 0.03);
            if (mx >= closeX && mx < closeX + 20 && my >= closeY && my < closeY + 20) {
                this.detailOverlayOpen = false;
                return true;
            }

            int btw = Math.min(120, (pw - 48) / 3);
            int configW = this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE) + 26;
            int websiteW = this.textRenderer.getWidth(this.selectedMod.getId().equals("minecraft") ? SEND_FEEDBACK_TEXT : ModMenuScreenTexts.WEBSITE) + 26;
            int issuesW = this.textRenderer.getWidth(this.selectedMod.getId().equals("minecraft") ? REPORT_BUGS_TEXT : ModMenuScreenTexts.ISSUES) + 26;
            int minBtw = Math.max(configW, Math.max(websiteW, issuesW));
            btw = Math.max(minBtw, btw);
            int bth = 26;
            int bty = apy + aph - bth - (int)(aph * 0.04);
            int centerX = apx + apw / 2;
            int groupW = 3 * btw + 8;
            int leftX = centerX - groupW / 2;

            int b1x = leftX;
            if (mx >= b1x && mx < b1x + btw && my >= bty && my < bty + bth) {
                if (getModHasConfigScreen(this.selectedMod.getId())) {
                    this.safelyOpenConfigScreen(this.selectedMod.getId());
                }
                return true;
            }

            int b2x = leftX + btw + 4;
            if (mx >= b2x && mx < b2x + btw && my >= bty && my < bty + bth) {
                String id = this.selectedMod.getId();
                if (id.equals("minecraft")) {
                    ConfirmLinkScreen.open(this, SharedConstants.getGameVersion().stable() ? Urls.JAVA_FEEDBACK : Urls.SNAPSHOT_FEEDBACK, true);
                } else {
                    String url = this.selectedMod.getWebsite();
                    if (url != null) ConfirmLinkScreen.open(this, url, false);
                }
                return true;
            }

            int b3x = leftX + 2 * btw + 8;
            if (mx >= b3x && mx < b3x + btw && my >= bty && my < bty + bth) {
                String id = this.selectedMod.getId();
                if (id.equals("minecraft")) {
                    ConfirmLinkScreen.open(this, Urls.SNAPSHOT_BUGS, true);
                } else {
                    String url = this.selectedMod.getIssueTracker();
                    if (url != null) ConfirmLinkScreen.open(this, url, false);
                }
                return true;
            }

            if (mx < apx || mx > apx + apw || my < apy || my > apy + aph) {
                this.detailOverlayOpen = false;
                return true;
            }
            return true;
        }

        if (this.filterOptionsShown) {
            int sortW = this.sortingButton.getWidth();
            int libW = this.librariesButton.getWidth();
            int popupWidth = Math.max(320, sortW + libW + 30);
            int popupHeight = 94;
            int popupX = (this.width - popupWidth) / 2;
            int popupY = (this.height - popupHeight) / 2;

            int closeX = popupX + popupWidth - 22;
            int closeY = popupY + 6;
            if (mx >= closeX && mx < closeX + 16 && my >= closeY && my < closeY + 16) {
                this.setFilterOptionsShown(false);
                return true;
            }

            int px = popupX + 12;
            int py = popupY + 26;
            if (mx >= px && mx < px + this.sortingButton.getWidth() && my >= py && my < py + 20) {
                ModMenuConfig.SORTING.cycleValue(this.client.isShiftPressed() ? -1 : 1);
                ModMenuConfigManager.save();
                this.sortingButton.setMessage(ModMenuConfig.SORTING.getButtonText());
                this.sortingButton.setWidth(this.textRenderer.getWidth(ModMenuConfig.SORTING.getButtonText()) + 26);
                this.modList.reloadFilters();
                this.cardEntries.clear();
                syncAnimArrays();
                return true;
            }

            int lx = px + this.sortingButton.getWidth() + 6;
            if (mx >= lx && mx < lx + this.librariesButton.getWidth() && my >= py && my < py + 20) {
                ModMenuConfig.SHOW_LIBRARIES.toggleValue();
                ModMenuConfigManager.save();
                this.librariesButton.setMessage(ModMenuConfig.SHOW_LIBRARIES.getButtonText());
                this.librariesButton.setWidth(this.textRenderer.getWidth(ModMenuConfig.SHOW_LIBRARIES.getButtonText()) + 26);
                this.modList.reloadFilters();
                this.cardEntries.clear();
                syncAnimArrays();
                return true;
            }

            int cfgBX = popupX + 12;
            int cfgBY = popupY + 66;
            int cfgBW = this.textRenderer.getWidth(ModMenuScreenTexts.CONFIGURE) + 26;
            if (mx >= cfgBX && mx < cfgBX + cfgBW && my >= cfgBY && my < cfgBY + 20) {
                this.safelyOpenConfigScreen("modmenu");
                return true;
            }

            if (mx < popupX || mx > popupX + popupWidth || my < popupY || my > popupY + popupHeight) {
                this.setFilterOptionsShown(false);
                return true;
            }
            return true;
        }

        // Filter button
        int fbx = sbX + sbW + 5;
        if (mx >= fbx && mx < fbx + 22 && my >= headerCenterY && my < headerCenterY + 22) {
            this.setFilterOptionsShown(!this.filterOptionsShown);
            return true;
        }

        // Check card clicks
        List<Mod> mods = getDisplayedMods();
        int total = mods.size();
        if (total == 0) return super.mouseClicked(click, doubleClick);

        int availH = H - headerH - 20;
        int cardH = Math.min(availH, Math.max(160, (int)(availH * 0.75)));
        int cardW = (int)(cardH * 0.88);
        int centerX = W / 2;
        int centerY = (H + headerH) / 2;

        // Offset for the drag tracking
        this.dragStartX = mx;

        // Hit-test cards from front to back (highest animScale first = focused)
        Integer clickedIndex = null;
        for (int i = 0; i < total; i++) {
            double dist = Math.abs(i - focusedIndex);
            double as = animScale.get(i);
            double ax = animX.get(i);
            double ay = animY.get(i);
            int cardX = (int) (centerX + ax - cardW * as / 2);
            int cardY = (int) (centerY + ay - cardH * as / 2);
            int cw = (int) (cardW * as);
            int ch = (int) (cardH * as);

            if (mx >= cardX && mx < cardX + cw && my >= cardY && my < cardY + ch) {
                if (clickedIndex == null || Math.abs(i - focusedIndex) < Math.abs(clickedIndex - focusedIndex)) {
                    clickedIndex = i;
                }
            }
        }

        if (clickedIndex != null) {
            if (clickedIndex == focusedIndex) {
                // Click focused card → open detail
                Mod mod = mods.get(clickedIndex);
                this.selectedMod = mod;
                this.detailOverlayOpen = true;
            } else {
                // Click non-focused card → make it focused
                focusedIndex = clickedIndex;
                this.detailOverlayOpen = false;
            }
            return true;
        }

        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public void close() {
        this.modList.close();
        this.client.setScreen(this.previousScreen);
    }

    private void setFilterOptionsShown(boolean filterOptionsShown) {
        this.filterOptionsShown = filterOptionsShown;
    }

    public ModListEntry getSelectedEntry() {
        return selected;
    }

    public void updateSelectedEntry(ModListEntry entry) {
        if (this.suppressSelectUpdate) return;
        if (entry == null) return;
        this.selected = entry;
        this.selectedMod = entry.getMod();
    }

    public void updateScrollPercent(double scrollPercent) {
    }

    public void selectModById(String modId) {
        for (ModListEntry entry : this.modList.children()) {
            if (entry.getMod().getId().equals(modId)) {
                this.modList.setSelected(entry);
                this.modList.ensureVisible(entry);
                return;
            }
        }
        Mod mod = ModMenu.MODS.get(modId);
        if (mod != null) {
            IndependentEntry tempEntry = new IndependentEntry(mod, this.modList);
            updateSelectedEntry(tempEntry);
        }
    }

    public String getSearchInput() {
        return this.searchBox.getText();
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        Path modsDirectory = FabricLoader.getInstance().getGameDir().resolve("mods");

        // Filter out none mods
        List<Path> mods = paths.stream().filter(ModsScreen::isValidMod).toList();
        if (mods.isEmpty()) {
            return;
        }

        String modList = mods.stream().map(Path::getFileName).map(Path::toString).collect(Collectors.joining(", "));
        assert this.client != null;
        this.client.setScreen(new ConfirmScreen((value) -> {
            if (value) {
                boolean allSuccessful = true;
                for (Path path : mods) {
                    try {
                        Files.copy(path, modsDirectory.resolve(path.getFileName()));
                    } catch (IOException e) {
                        LOGGER.warn("Failed to copy mod from {} to {}", path, modsDirectory.resolve(path.getFileName()));
                        SystemToast.addPackCopyFailure(this.client, path.toString());
                        allSuccessful = false;
                        break;
                    }
                }

                if (allSuccessful) {
                    SystemToast.add(this.client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, ModMenuScreenTexts.DROP_SUCCESSFUL_LINE_1, ModMenuScreenTexts.DROP_SUCCESSFUL_LINE_2);
                }
            }
            this.client.setScreen(this);
        }, ModMenuScreenTexts.DROP_CONFIRM, Text.literal(modList)));
    }

    private static boolean isValidMod(Path mod) {
        try (JarFile jarFile = new JarFile(mod.toFile())) {
            var isFabricMod = jarFile.getEntry("fabric.mod.json") != null;
            if (!ModMenu.RUNNING_QUILT) {
                return isFabricMod;
            } else {
                return isFabricMod || jarFile.getEntry("quilt.mod.json") != null;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static Path getModsFolder() {
        ModContainer container = FabricLoader.getInstance().getModContainer(ModMenu.MOD_ID).orElseThrow();

        while (container.getContainingMod().isPresent()) {
            container = container.getContainingMod().get();
        }

        if (container.getOrigin().getKind() == ModOrigin.Kind.PATH) {
            return container.getOrigin().getPaths().getFirst().getParent();
        } else {
            // Fall back on the old behavior
            return FabricLoader.getInstance().getGameDir().resolve("mods");
        }
    }

    public boolean getModHasConfigScreen(String modId) {
        if (this.modScreenErrors.containsKey(modId)) {
            return false;
        } else {
            return this.modHasConfigScreen.computeIfAbsent(modId, ModMenu::hasConfigScreen);
        }
    }

    public void safelyOpenConfigScreen(String modId) {
        try {
            Screen screen = ModMenu.getConfigScreen(modId, this);
            if (screen != null) {
                assert this.client != null;
                this.client.setScreen(screen);
            }
        } catch (java.lang.NoClassDefFoundError e) {
            LOGGER.warn("The '{}' mod config screen is not available because {} is missing.", modId, e.getLocalizedMessage());
            modScreenErrors.put(modId, e);
        } catch (Throwable e) {
            LOGGER.error("Error from mod '{}'", modId, e);
            modScreenErrors.put(modId, e);
        }
    }
}
