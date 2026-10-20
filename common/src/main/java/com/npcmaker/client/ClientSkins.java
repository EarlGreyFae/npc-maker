package com.npcmaker.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.npcmaker.Constants;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.SkinPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skins from the server's skin library on this client: each picture is asked for once when an NPC wearing it first
 * shows up, then kept as a texture. Re-uploads replace the texture straight away. Also holds the library's names for
 * the editor.
 */
public final class ClientSkins {

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();
    private static List<String> library = List.of();
    private static @Nullable ClientLevel lastLevel;

    private ClientSkins() {
    }

    /** The library skin as a player skin, or null while it's still on its way (draw the default meanwhile). */
    public static @Nullable PlayerSkin get(String name, PlayerModelType model) {
        forgetIfNewWorld();
        Identifier id = TEXTURES.get(name);
        if (id == null) {
            if (REQUESTED.add(name)) {
                NpcNetworking.sendToServer(new SkinPayloads.Request(name));
            }
            return null;
        }
        ClientAsset.Texture texture = new ClientAsset.ResourceTexture(id, id);
        return new PlayerSkin(texture, null, null, model, true);
    }

    /** A new world or server may have different skins under the same names, so ask again there. */
    private static void forgetIfNewWorld() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            lastLevel = level;
            REQUESTED.clear();
            TEXTURES.keySet().forEach(name -> REQUESTED.remove(name));
            Minecraft mc = Minecraft.getInstance();
            TEXTURES.values().forEach(mc.getTextureManager()::release);
            TEXTURES.clear();
        }
    }

    public static void onData(SkinPayloads.Data data) {
        Minecraft mc = Minecraft.getInstance();
        Identifier id = Constants.id("npc_skins/" + data.name());
        if (data.png().length == 0) {
            return; // not in the library; keep drawing the default skin
        }
        try {
            NativeImage image = NativeImage.read(data.png());
            if (TEXTURES.containsKey(data.name())) {
                mc.getTextureManager().release(id);
            }
            mc.getTextureManager().register(id, new DynamicTexture(() -> "NPC Maker skin " + data.name(), image));
            TEXTURES.put(data.name(), id);
            REQUESTED.add(data.name());
        } catch (Exception e) {
            Constants.LOG.warn("Couldn't load skin '{}' from the server", data.name(), e);
        }
    }

    public static void onLibrary(SkinPayloads.Library payload) {
        library = List.copyOf(payload.names());
        NpcEditorScreen.skinLibraryChanged(payload.added());
    }

    public static List<String> library() {
        return library;
    }
}
