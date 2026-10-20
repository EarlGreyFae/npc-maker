package com.npcmaker.client;

import com.npcmaker.custom.AnimationClip;
import com.npcmaker.custom.CustomContent;
import com.npcmaker.network.CustomPayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The custom content library: every sound, particle texture, Blockbench model and animation file uploaded to this
 * world. Upload with the button (pick several files at once) or by dropping files on the screen; delete with ✖ (two
 * clicks). Uploads go to the server, which sends them to every player's game. Uses NpcUiTheme.
 */
public class LibraryScreen extends Screen implements ClientContent.Listener {

    private static final int PAD = 8;
    private static final int ROW_H = 14;
    private static final int BUTTON_H = 14;
    private static final int SIDE_W = 104;
    private static final long CONFIRM_MS = 3000L;
    private static final String[] KINDS = {"sound", "particle", "model", "animation"};
    private static final String[] KIND_LABELS = {"Sounds", "Particles", "Models", "Animations"};

    private final Screen parent;
    private int kind;
    private int scroll;
    private @Nullable String armedDelete;
    private long armedAt;
    private Component status = Component.empty();
    private int statusColor = NpcUiTheme.BORDER_DIM;
    private final HelpLayer help = new HelpLayer();

    public LibraryScreen(Screen parent) {
        super(Component.literal("Custom content library"));
        this.parent = parent;
    }

    /** The server's list changed: nothing to rebuild, the screen reads it every frame. */
    static void refresh() {
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int panelW() {
        return Math.min(460, width - 16);
    }

    private int panelH() {
        return Math.min(290, height - 16);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int listX() {
        return panelX() + SIDE_W + PAD;
    }

    private int listW() {
        return panelW() - SIDE_W - PAD * 2;
    }

    private int listY() {
        return panelY() + 26;
    }

    private int footerY() {
        return panelY() + panelH() - PAD - BUTTON_H;
    }

    private int visibleRows() {
        return Math.max(1, (footerY() - 30 - listY()) / ROW_H);
    }

    // ---- data ---------------------------------------------------------------------------------------------

    private record Row(String name, String detail, List<String> paths) {
    }

    /** Rows for the chosen kind; a model's texture rides along with it. */
    private List<Row> rows() {
        CustomPayloads.Manifest manifest = ClientCustom.manifest();
        String k = KINDS[kind];
        List<Row> rows = new ArrayList<>();
        for (CustomPayloads.FileEntry f : manifest.files()) {
            if (!k.equals(CustomContent.kindOf(f.path()))) {
                continue;
            }
            String name = CustomContent.baseName(f.path());
            String size = f.size() >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", f.size() / 1048576.0) : (f.size() / 1024 + 1) + " KB";
            List<String> paths = new ArrayList<>(List.of(f.path()));
            String detail = size;
            switch (k) {
                case "particle" -> detail = manifest.particles().stream().filter(s -> s.name().equals(name)).findFirst()
                        .map(s -> "slot " + s.slot()).orElse("no slot") + " · " + size;
                case "model" -> {
                    boolean textured = manifest.files().stream().anyMatch(t -> t.path().equals("models/" + name + ".png"));
                    detail = (textured ? "textured" : "needs " + name + ".png") + " · " + size;
                    if (textured) {
                        paths.add("models/" + name + ".png");
                    }
                }
                case "animation" -> {
                    List<String> clips = CustomModels.animationNames().stream()
                            .filter(n -> n.contains(name)).map(n -> n.substring(n.lastIndexOf('.') + 1)).toList();
                    detail = clips.isEmpty() ? size : String.join(", ", clips);
                }
                default -> {
                }
            }
            rows.add(new Row(name, detail, paths));
        }
        rows.sort(Comparator.comparing(Row::name));
        return rows;
    }

    // ---- uploading ----------------------------------------------------------------------------------------

    private static @Nullable SDL_DialogFileCallback fileCallback;

    private void browse() {
        if (fileCallback != null) {
            return;
        }
        fileCallback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            List<String> chosen = new ArrayList<>();
            if (fileList != 0L) {
                for (int i = 0; ; i++) {
                    long entry = MemoryUtil.memGetAddress(fileList + (long) i * Long.BYTES);
                    if (entry == 0L) {
                        break;
                    }
                    chosen.add(MemoryUtil.memUTF8(entry));
                }
            }
            Minecraft.getInstance().execute(() -> {
                SDL_DialogFileCallback done = fileCallback;
                fileCallback = null;
                if (done != null) {
                    done.free();
                }
                if (!chosen.isEmpty() && Minecraft.getInstance().gui.screen() instanceof LibraryScreen library) {
                    library.upload(chosen.stream().map(Path::of).toList());
                }
            });
        });
        SDLDialog.SDL_ShowOpenFileDialog(fileCallback, 0L, minecraft.getWindow().handle(),
                (SDL_DialogFileFilter.Buffer) null, (CharSequence) null, true);
        status = Component.literal("Choose files: .ogg sounds, .png pictures, .geo.json models, .animation.json animations…");
        statusColor = NpcUiTheme.BORDER_DIM;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        upload(files);
    }

    /** Sends files to the server in pieces: models first, so a model's PNG is recognised as its texture. */
    private void upload(List<Path> files) {
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingInt(LibraryScreen::order));
        int sent = 0;
        for (Path file : sorted) {
            String name = file.getFileName().toString();
            if (order(file) > 3) {
                showResult(false, name + ": NPC Maker takes .ogg, .png, .geo.json and .animation.json files.");
                continue;
            }
            try {
                byte[] data = Files.readAllBytes(file);
                int parts = Math.max(1, (data.length + CustomPayloads.UP_CHUNK - 1) / CustomPayloads.UP_CHUNK);
                if (parts > 200) {
                    showResult(false, name + " is too big.");
                    continue;
                }
                for (int part = 0; part < parts; part++) {
                    int from = part * CustomPayloads.UP_CHUNK;
                    byte[] piece = Arrays.copyOfRange(data, from, Math.min(data.length, from + CustomPayloads.UP_CHUNK));
                    NpcNetworking.sendToServer(new CustomPayloads.UploadPart(name, part, parts, piece));
                }
                sent++;
            } catch (Exception e) {
                showResult(false, "Couldn't read " + name + ".");
            }
        }
        if (sent > 0) {
            status = Component.literal("Uploading " + sent + " file" + (sent == 1 ? "" : "s") + "…");
            statusColor = NpcUiTheme.BORDER_DIM;
        }
    }

    private static int order(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".geo.json")) {
            return 0;
        } else if (n.endsWith(".animation.json")) {
            return 1;
        } else if (n.endsWith(".ogg")) {
            return 2;
        } else if (n.endsWith(".png")) {
            return 3;
        }
        return 9;
    }

    @Override
    public void showResult(boolean ok, String message) {
        status = Component.literal(message);
        statusColor = ok ? 0xFF7FD36A : 0xFFE04848;
    }

    // ---- input --------------------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private record Button(int x, int w, String label) {
    }

    private List<Button> buttons() {
        List<Button> list = new ArrayList<>();
        int x = panelX() + PAD;
        for (String label : List.of("Upload files…", "Back")) {
            int w = font.width(label) + 16;
            list.add(new Button(x, w, label));
            x += w + 5;
        }
        return list;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        for (int i = 0; i < KINDS.length; i++) {
            if (inside(mx, my, panelX() + 4, listY() + i * 18, SIDE_W - 8, 15)) {
                kind = i;
                scroll = 0;
                armedDelete = null;
                return true;
            }
        }
        List<Row> rows = rows();
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
            Row row = rows.get(i);
            int ry = listY() + (i - scroll) * ROW_H;
            if (inside(mx, my, listX() + listW() - 12, ry, 12, ROW_H)) {
                if (row.name().equals(armedDelete) && Util.getMillis() - armedAt < CONFIRM_MS) {
                    row.paths().forEach(p -> NpcNetworking.sendToServer(new CustomPayloads.Delete(p)));
                    armedDelete = null;
                } else {
                    armedDelete = row.name();
                    armedAt = Util.getMillis();
                    showResult(false, "Click ✖ again to delete '" + row.name() + "' for everyone.");
                    statusColor = 0xFFE0A048;
                }
                return true;
            }
            if (KINDS[kind].equals("sound") && inside(mx, my, listX() + listW() - 28, ry, 14, ROW_H)) {
                PickListScreen.playSound(CustomContent.NAMESPACE + ":" + row.name());
                return true;
            }
        }
        for (Button b : buttons()) {
            if (inside(mx, my, b.x(), footerY(), b.w(), BUTTON_H)) {
                if (b.label().startsWith("Upload")) {
                    browse();
                } else {
                    onClose();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 2, 0, Math.max(0, rows().size() - visibleRows()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && help.escape()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        help.begin();
        int x = panelX();
        int y = panelY();
        int w = panelW();
        int h = panelH();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        graphics.text(font, "Custom content library", x + PAD, y + 9, NpcUiTheme.ACCENT, false);
        help.button(x + w - PAD - 12, y + 7);
        int waiting = ClientCustom.downloading();
        String note = waiting > 0 ? "Downloading " + waiting + " file" + (waiting == 1 ? "" : "s") + "…" : "Shared with everyone on this server";
        graphics.text(font, note, x + w - PAD - 18 - font.width(note), y + 9, waiting > 0 ? 0xFFE0A048 : NpcUiTheme.BORDER_DIM, false);
        graphics.fill(x + 4, listY() - 4, x + w - 4, listY() - 3, NpcUiTheme.BORDER_DIM);

        // kinds
        for (int i = 0; i < KINDS.length; i++) {
            int ty = listY() + i * 18;
            boolean selected = i == kind;
            boolean hover = inside(mouseX, mouseY, x + 4, ty, SIDE_W - 8, 15);
            if (selected || hover) {
                graphics.fill(x + 4, ty, x + SIDE_W - 4, ty + 15, NpcUiTheme.PANEL_INSET);
                graphics.outline(x + 4, ty, SIDE_W - 8, 15, selected ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            }
            int count = ClientCustom.names(KINDS[i]).size();
            graphics.text(font, KIND_LABELS[i] + " (" + count + ")", x + 10, ty + 4, selected ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
            help.spot(x + 4, ty, SIDE_W - 8, 15, "library.kind." + KINDS[i]);
        }
        graphics.fill(x + SIDE_W, listY(), x + SIDE_W + 1, footerY() - 8, NpcUiTheme.BORDER_DIM);

        // files
        List<Row> rows = rows();
        int lx = listX();
        int lw = listW();
        graphics.fill(lx, listY() - 1, lx + lw, listY() + visibleRows() * ROW_H + 1, NpcUiTheme.PANEL_INSET);
        help.spot(lx, listY(), lw - 30, visibleRows() * ROW_H, "library.list");
        boolean armed = armedDelete != null && Util.getMillis() - armedAt < CONFIRM_MS;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
            Row row = rows.get(i);
            int ry = listY() + (i - scroll) * ROW_H;
            boolean hover = inside(mouseX, mouseY, lx, ry, lw, ROW_H);
            if (hover) {
                graphics.fill(lx, ry, lx + lw, ry + ROW_H, 0x30D4AF37);
            }
            String name = font.plainSubstrByWidth(row.name(), lw / 2);
            graphics.text(font, name, lx + 4, ry + 3, NpcUiTheme.TEXT, false);
            int detailRoom = lw - font.width(name) - 50;
            graphics.text(font, font.plainSubstrByWidth(row.detail(), Math.max(0, detailRoom)), lx + 12 + font.width(name), ry + 3, 0xFF8F8A80, false);
            if (KINDS[kind].equals("sound")) {
                boolean overPlay = inside(mouseX, mouseY, lx + lw - 28, ry, 14, ROW_H);
                graphics.text(font, "▶", lx + lw - 25, ry + 3, overPlay ? 0xFFFFE08A : NpcUiTheme.BORDER_DIM, false);
                help.spot(lx + lw - 28, ry, 14, ROW_H, "library.play");
            }
            boolean overX = inside(mouseX, mouseY, lx + lw - 12, ry, 12, ROW_H);
            boolean thisArmed = armed && row.name().equals(armedDelete);
            graphics.text(font, "✖", lx + lw - 10, ry + 3, overX || thisArmed ? 0xFFE04848 : 0xFF8F8A80, false);
            help.spot(lx + lw - 12, ry, 12, ROW_H, "library.delete");
        }
        if (rows.isEmpty()) {
            String empty = switch (KINDS[kind]) {
                case "sound" -> "No sounds yet. Upload .ogg files.";
                case "particle" -> "No particles yet. Upload .png pictures (tall strips animate).";
                case "model" -> "No models yet. Upload a .geo.json with its .png texture.";
                default -> "No animations yet. Upload .animation.json files.";
            };
            graphics.text(font, empty, lx + 4, listY() + 3, NpcUiTheme.BORDER_DIM, false);
        }
        int ty = listY() + visibleRows() * ROW_H + 5;
        String tip = switch (KINDS[kind]) {
            case "sound" -> "Use them in any sound picker (npcmaker_custom:…).";
            case "particle" -> "Use them in any particle picker. Up to " + CustomContent.PARTICLE_SLOTS + ".";
            case "model" -> "Pick them in Look → Looks like. Name the .png like the model.";
            default -> "Play them as emotes, idle poses, from rules and dialogue.";
        };
        graphics.text(font, font.plainSubstrByWidth(tip, lw), lx, ty, 0xFF6E6578, false);

        // footer
        for (Button b : buttons()) {
            boolean hover = inside(mouseX, mouseY, b.x(), footerY(), b.w(), BUTTON_H);
            graphics.fill(b.x(), footerY(), b.x() + b.w(), footerY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), footerY(), b.w(), BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, footerY() + 3, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
            help.spot(b.x(), footerY(), b.w(), BUTTON_H, b.label().startsWith("Upload") ? "library.upload" : "library.back");
        }
        int leftmost = buttons().get(buttons().size() - 1).x() + buttons().get(buttons().size() - 1).w() + 8;
        String shown = status.getString().isEmpty() ? "Or drop files anywhere on this screen." : status.getString();
        graphics.text(font, font.plainSubstrByWidth(shown, x + w - PAD - leftmost), leftmost, footerY() + 3,
                status.getString().isEmpty() ? 0xFF6E6578 : statusColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }
}
