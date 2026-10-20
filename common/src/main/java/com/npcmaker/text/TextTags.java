package com.npcmaker.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Simple styling tags for NPC text, readable when hand-written:
 * <ul>
 *   <li>colours: {@code <gold>…</gold>}, any vanilla colour name ({@code red}, {@code dark_green}, {@code aqua}…),
 *       {@code <purple>} / {@code <grey>} aliases, or hex {@code <#C8A24A>…</#C8A24A>}</li>
 *   <li>{@code <b>} bold, {@code <i>} italic, {@code <u>} underline, {@code <s>} strikethrough,
 *       {@code <obf>} scrambled</li>
 * </ul>
 * Any closing tag ({@code </gold>}, {@code </b>}, even {@code </>}) ends the most recent open tag. Unknown tags
 * stay in the text as written, so a stray {@code <3} is safe. {@code \<} writes a literal {@code <}.
 */
public final class TextTags {

    private TextTags() {
    }

    public static MutableComponent parse(String text) {
        MutableComponent out = Component.empty();
        Deque<Style> stack = new ArrayDeque<>();
        stack.push(Style.EMPTY);
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '<') {
                run.append('<');
                i += 2;
                continue;
            }
            if (c == '<') {
                int close = text.indexOf('>', i);
                if (close > i) {
                    String tag = text.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
                    if (tag.startsWith("/")) {
                        flush(out, run, stack.peek());
                        if (stack.size() > 1) {
                            stack.pop();
                        }
                        i = close + 1;
                        continue;
                    }
                    UnaryOperator<Style> change = styleFor(tag);
                    if (change != null) {
                        flush(out, run, stack.peek());
                        stack.push(change.apply(stack.peek()));
                        i = close + 1;
                        continue;
                    }
                }
            }
            run.append(c);
            i++;
        }
        flush(out, run, stack.peek());
        return out;
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
            run.setLength(0);
        }
    }

    private static UnaryOperator<Style> styleFor(String tag) {
        switch (tag) {
            case "b", "bold":
                return s -> s.withBold(true);
            case "i", "italic":
                return s -> s.withItalic(true);
            case "u", "underline":
                return s -> s.withUnderlined(true);
            case "s", "strikethrough":
                return s -> s.withStrikethrough(true);
            case "obf", "obfuscated":
                return s -> s.withObfuscated(true);
            case "purple":
                tag = "light_purple";
                break;
            case "grey":
                tag = "gray";
                break;
            case "dark_grey":
                tag = "dark_gray";
                break;
            default:
                break;
        }
        // Handles both vanilla colour names and #RRGGBB.
        return TextColor.parseColor(tag).result()
                .<UnaryOperator<Style>>map(color -> s -> s.withColor(color))
                .orElse(null);
    }
}
