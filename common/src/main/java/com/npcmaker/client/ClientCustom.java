package com.npcmaker.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.npcmaker.Constants;
import com.npcmaker.custom.CustomContent;
import com.npcmaker.network.CustomPayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The player's side of custom content. When the server sends its list of files, this downloads the ones not already
 * cached ({@code <game folder>/npcmaker_cache/files}, by SHA-1, so each file only ever downloads once), then builds a
 * resource pack folder from them and reloads resources:
 * <ul>
 *   <li>sounds → {@code assets/npcmaker_custom/sounds/} plus a generated {@code sounds.json}</li>
 *   <li>particle PNGs → split into square frames, and the particle slot's description pointed at them</li>
 *   <li>model PNGs → entity textures; .geo.json models and animations are read straight from the cache</li>
 * </ul>
 * The pack is always on and sits above other packs; it holds only NPC Maker's own namespace.
 */
public final class ClientCustom {

    private static CustomPayloads.Manifest manifest = new CustomPayloads.Manifest(List.of(), List.of());
    private static final Map<String, byte[][]> INCOMING = new HashMap<>();
    private static final Set<String> WAITING = new LinkedHashSet<>();
    /** What the pack folder was last built from, to skip needless reloads. */
    private static String builtFrom = "";

    private ClientCustom() {
    }

    public static CustomPayloads.Manifest manifest() {
        return manifest;
    }

    private static Path cache() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("npcmaker_cache");
    }

    private static Path packDir() {
        return cache().resolve("pack");
    }

    static Path cached(String sha1) {
        return cache().resolve("files").resolve(sha1);
    }

    /** Names of custom files of one kind (sound, particle, model, animation), from the server's list. */
    public static List<String> names(String kind) {
        List<String> list = new ArrayList<>();
        for (CustomPayloads.FileEntry f : manifest.files()) {
            if (kind.equals(CustomContent.kindOf(f.path()))) {
                list.add(CustomContent.baseName(f.path()));
            }
        }
        return list;
    }

    // ---- receiving ----------------------------------------------------------------------------------------

    public static void onManifest(CustomPayloads.Manifest payload) {
        manifest = payload;
        WAITING.clear();
        INCOMING.clear();
        for (CustomPayloads.FileEntry f : payload.files()) {
            if (!Files.exists(cached(f.sha1()))) {
                WAITING.add(f.sha1());
            }
        }
        if (WAITING.isEmpty()) {
            build();
        } else {
            NpcNetworking.sendToServer(new CustomPayloads.RequestFiles(List.copyOf(WAITING)));
        }
        LibraryScreen.refresh();
    }

    public static void onFilePart(CustomPayloads.FilePart part) {
        if (!WAITING.contains(part.sha1()) || part.parts() <= 0 || part.part() >= part.parts()) {
            return;
        }
        byte[][] parts = INCOMING.computeIfAbsent(part.sha1(), k -> new byte[part.parts()][]);
        if (parts.length != part.parts()) {
            return;
        }
        parts[part.part()] = part.data();
        for (byte[] p : parts) {
            if (p == null) {
                return;
            }
        }
        INCOMING.remove(part.sha1());
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] data = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, data, at, p.length);
            at += p.length;
        }
        if (!CustomContent.sha1(data).equals(part.sha1())) {
            Constants.LOG.warn("Custom file {} arrived damaged; skipping it", part.sha1());
        } else {
            try {
                Files.createDirectories(cached(part.sha1()).getParent());
                Files.write(cached(part.sha1()), data);
            } catch (IOException e) {
                Constants.LOG.error("Could not cache custom file {}", part.sha1(), e);
            }
        }
        WAITING.remove(part.sha1());
        if (WAITING.isEmpty()) {
            build();
        }
    }

    /** Whether files are still downloading (for the library screen). */
    public static int downloading() {
        return WAITING.size();
    }

    // ---- building the pack --------------------------------------------------------------------------------

    private static void build() {
        StringBuilder key = new StringBuilder();
        manifest.files().forEach(f -> key.append(f.path()).append('=').append(f.sha1()).append(';'));
        manifest.particles().forEach(s -> key.append(s.name()).append('@').append(s.slot()).append(';'));
        CustomModels.reload(manifest);
        if (key.toString().equals(builtFrom) && (manifest.files().isEmpty() || Files.exists(packDir()))) {
            return;
        }
        boolean hadPack = Files.exists(packDir());
        try {
            deleteTree(packDir());
            if (!manifest.files().isEmpty()) {
                writePack();
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not build the custom content pack", e);
            return;
        }
        builtFrom = key.toString();
        if (hadPack || !manifest.files().isEmpty()) {
            Minecraft.getInstance().reloadResourcePacks();
        }
    }

    private static void writePack() throws IOException {
        Path root = packDir();
        Path assets = root.resolve("assets").resolve(CustomContent.NAMESPACE);
        Files.createDirectories(assets);
        // the NPC Maker icon, so the pack is recognisable in the resource pack list
        try (java.io.InputStream icon = ClientCustom.class.getResourceAsStream("/assets/" + Constants.MOD_ID + "/icon.png")) {
            if (icon != null) {
                Files.copy(icon, root.resolve("pack.png"));
            }
        }
        JsonObject sounds = new JsonObject();
        Map<String, Integer> slots = new HashMap<>();
        manifest.particles().forEach(s -> slots.put(s.name(), s.slot()));
        for (CustomPayloads.FileEntry f : manifest.files()) {
            Path source = cached(f.sha1());
            if (!Files.exists(source)) {
                continue;
            }
            String kind = CustomContent.kindOf(f.path());
            String name = CustomContent.baseName(f.path());
            if (kind == null) {
                continue;
            }
            switch (kind) {
                case "sound" -> {
                    copy(source, assets.resolve("sounds").resolve(name + ".ogg"));
                    JsonObject event = new JsonObject();
                    JsonArray list = new JsonArray();
                    list.add(CustomContent.NAMESPACE + ":" + name);
                    event.add("sounds", list);
                    sounds.add(name, event);
                }
                case "particle" -> {
                    Integer slot = slots.get(name);
                    if (slot != null) {
                        writeParticle(root, assets, name, slot, Files.readAllBytes(source));
                    }
                }
                case "texture" -> copy(source, assets.resolve("textures/entity").resolve(name + ".png"));
                case "model" -> copy(source, assets.resolve("geo").resolve(name + ".geo.json"));
                case "animation" -> copy(source, assets.resolve("animations").resolve(name + ".animation.json"));
                default -> {
                }
            }
        }
        if (!sounds.isEmpty()) {
            Files.writeString(assets.resolve("sounds.json"), new GsonBuilder().setPrettyPrinting().create().toJson(sounds));
        }
    }

    /** A tall PNG (height a multiple of width) becomes square frames played over the particle's life. */
    private static void writeParticle(Path root, Path assets, String name, int slot, byte[] png) throws IOException {
        Path textures = assets.resolve("textures/particle");
        Files.createDirectories(textures);
        JsonArray frames = new JsonArray();
        try (NativeImage image = NativeImage.read(png)) {
            int w = image.getWidth();
            int h = image.getHeight();
            int count = h > w && h % w == 0 ? Math.min(64, h / w) : 1;
            int size = count > 1 ? w : h;
            for (int k = 0; k < count; k++) {
                try (NativeImage frame = new NativeImage(w, size, true)) {
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < w; x++) {
                            frame.setPixel(x, y, image.getPixel(x, k * size + y));
                        }
                    }
                    frame.writeToFile(textures.resolve(name + "_" + k + ".png"));
                }
                frames.add(CustomContent.NAMESPACE + ":" + name + "_" + k);
            }
        }
        JsonObject description = new JsonObject();
        description.add("textures", frames);
        Path particles = root.resolve("assets").resolve(Constants.MOD_ID).resolve("particles");
        Files.createDirectories(particles);
        Files.writeString(particles.resolve("custom_" + slot + ".json"), description.toString());
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Reads a cached custom file's text ("" if it isn't cached). */
    static String readText(String sha1) {
        try {
            return Files.readString(cached(sha1), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- the pack in the game's pack list ------------------------------------------------------------------

    /** Offers the custom content pack to the game's resource pack list (added by PackRepositoryMixin). */
    public static final RepositorySource SOURCE = new RepositorySource() {
        @Override
        public void loadPacks(Consumer<Pack> onLoad) {
            Path dir = packDir();
            if (!Files.isDirectory(dir.resolve("assets"))) {
                return;
            }
            PackLocationInfo info = new PackLocationInfo("npcmaker_custom", Component.literal("NPC Maker custom content"),
                    PackSource.SERVER, Optional.empty());
            Pack.Metadata meta = new Pack.Metadata(Component.literal("Sounds, particles and models from the server"),
                    PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
            onLoad.accept(new Pack(info, new PathPackResources.PathResourcesSupplier(dir), meta,
                    new PackSelectionConfig(true, Pack.Position.TOP, false)));
        }
    };
}
