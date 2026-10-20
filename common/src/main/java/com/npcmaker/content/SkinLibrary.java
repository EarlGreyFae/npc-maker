package com.npcmaker.content;

import com.npcmaker.Constants;
import net.minecraft.server.MinecraftServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The world's shared skin library: PNG skins in {@code <world>/npcmaker/skins/<name>.png}, uploaded from the editor
 * (or dropped in by hand) and usable by any NPC with {@code "appearance": {"skin_file": "<name>"}}. Every player
 * gets the picture from the server when they first see an NPC wearing it.
 */
public final class SkinLibrary {

    /** Uploads are sent in one packet, so they must stay under the client-to-server packet limit. */
    public static final int MAX_BYTES = 30_000;
    private static final int MAX_NAME = 32;

    private static Path dir;

    private SkinLibrary() {
    }

    public static void init(MinecraftServer server) {
        dir = NpcContent.root(server).resolve("skins");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Constants.LOG.error("Could not create skin folder {}", dir, e);
        }
    }

    public static List<String> names() {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .filter(ContentFolder::isValidId)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static Optional<byte[]> read(String name) {
        if (dir == null || !ContentFolder.isValidId(name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(name + ".png");
        try {
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            Constants.LOG.error("Could not read skin {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * Checks and stores an uploaded skin under a name made from {@code wanted}. Re-uploading the same picture
     * reuses its name; a different picture with a taken name gets "_2", "_3"… Returns the name it was stored as.
     *
     * @throws IOException with a message for the player if the file isn't a usable skin
     */
    public static String save(String wanted, byte[] png) throws IOException {
        if (dir == null) {
            throw new IOException("The skin library isn't ready yet.");
        }
        if (png.length == 0 || png.length > MAX_BYTES) {
            throw new IOException("Skin files must be PNGs under 30 KB.");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            image = null;
        }
        if (image == null) {
            throw new IOException("That file isn't a PNG image.");
        }
        if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IOException("Skins must be 64×64 (or old-style 64×32) pixels; this one is "
                    + image.getWidth() + "×" + image.getHeight() + ".");
        }
        String base = sanitise(wanted);
        String name = base;
        for (int n = 2; ; n++) {
            Optional<byte[]> existing = read(name);
            if (existing.isEmpty()) {
                break;
            }
            if (Arrays.equals(existing.get(), png)) {
                return name; // same picture, already in the library
            }
            name = base + "_" + n;
        }
        Files.write(dir.resolve(name + ".png"), png);
        Constants.LOG.info("Added skin '{}' to the library", name);
        return name;
    }

    /** "My Cool Skin (1).png" → "my_cool_skin_1". */
    private static String sanitise(String wanted) {
        String s = wanted.toLowerCase(Locale.ROOT);
        if (s.endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        }
        s = s.replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME);
        }
        return s.isEmpty() ? "skin" : s;
    }
}
