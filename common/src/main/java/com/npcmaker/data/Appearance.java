package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * How an NPC looks.
 *
 * @param skinUsername Minecraft username whose skin the NPC wears; empty picks a vanilla default skin from the
 *                     NPC's template id, so every NPC of one template looks the same
 * @param skinFile     a skin from the world's skin library ({@code npcmaker/skins/<name>.png}); wins over the username
 * @param model        another mob to look and animate like, e.g. {@code "minecraft:spider"}; empty = player model
 * @param arms         arm width
 * @param nameColor    name tag colour as #RRGGBB
 * @param showName     whether the name tag is always visible (otherwise only when looked at)
 * @param scale        model scale, 1.0 = player size
 */
public record Appearance(Optional<String> skinUsername, Optional<String> skinFile, Optional<String> model, ArmStyle arms,
                         String nameColor, boolean showName, float scale) {

    public static final Appearance DEFAULT = new Appearance(Optional.empty(), Optional.empty(), Optional.empty(), ArmStyle.AUTO,
            "#FFFFFF", true, 1.0F);

    public static final Codec<Appearance> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("skin_username").forGetter(Appearance::skinUsername),
            Codec.STRING.optionalFieldOf("skin_file").forGetter(Appearance::skinFile),
            Codec.STRING.optionalFieldOf("model").forGetter(Appearance::model),
            ArmStyle.CODEC.optionalFieldOf("arms", ArmStyle.AUTO).forGetter(Appearance::arms),
            Codec.STRING.optionalFieldOf("name_color", "#FFFFFF").forGetter(Appearance::nameColor),
            Codec.BOOL.optionalFieldOf("show_name", true).forGetter(Appearance::showName),
            Codec.floatRange(0.25F, 4.0F).optionalFieldOf("scale", 1.0F).forGetter(Appearance::scale)
    ).apply(i, Appearance::new));

    /** The name colour as 0xRRGGBB; falls back to white if the text isn't a valid colour. */
    public int nameColorRgb() {
        try {
            return Integer.parseInt(nameColor.startsWith("#") ? nameColor.substring(1) : nameColor, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
