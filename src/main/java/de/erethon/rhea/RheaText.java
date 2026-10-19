package de.erethon.rhea;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Measures and wraps live text against a font bundled in a Rhea pack. */
public final class RheaText {

    private final Font font;
    private final int oversample;
    private final FontRenderContext metrics = new FontRenderContext(null, true, false);

    /**
     * Loads a font with the same size and oversample as its resource-pack provider.
     * @param fontResource bundled TTF input
     * @param size logical font size
     * @param oversample font sampling factor, 2 or 4
     */
    public RheaText(InputStream fontResource, float size, int oversample) {
        if (fontResource == null) throw new IllegalArgumentException("Missing body font");
        if (size < 1 || (oversample != 2 && oversample != 4))
            throw new IllegalArgumentException("Invalid body font size or oversample");
        try (fontResource) {
            font = Font.createFont(Font.TRUETYPE_FONT, fontResource).deriveFont((float) size * oversample);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot read body font", ex);
        }
        this.oversample = oversample;
    }

    /** Measures a plain line and removes its text shadow.
     * @param value text to render
     * @param color text color
     * @param bold whether to use Minecraft bold
     * @return measured line
     */
    public Rhea.SlotLine line(String value, TextColor color, boolean bold) {
        Component component = Component.text(value, color).shadowColor(ShadowColor.none());
        if (bold) component = component.decorate(TextDecoration.BOLD);
        return new Rhea.SlotLine(component, width(value, bold));
    }

    /** Returns the Minecraft font advance, rounded to its quarter-pixel grid.
     * @param value text to measure
     * @param bold whether to use Minecraft bold
     * @return advance in dialog pixels
     */
    public double width(String value, boolean bold) {
        double advance = font.getStringBounds(value, metrics).getWidth() / oversample;
        return Math.rint(advance * 4) / 4 + (bold ? value.codePointCount(0, value.length()) : 0);
    }

    /** Wraps text into as many lines as needed. Styles and click events survive wrapping.
     * Only plain text components and their children are supported.
     * @param value text to wrap
     * @param maxWidth slot width in pixels
     * @return measured lines, including explicit blank lines
     */
    public List<Rhea.SlotLine> wrap(Component value, int maxWidth) {
        if (maxWidth < 1) throw new IllegalArgumentException("Slot width must be positive");
        List<Token> tokens = new ArrayList<>();
        collect(value, List.of(), false, tokens);
        List<Rhea.SlotLine> lines = new ArrayList<>();
        Line current = new Line();
        List<Token> spaces = new ArrayList<>();
        boolean endedWithNewline = false;
        for (Token token : tokens) {
            if (token.newline()) {
                lines.add(current.result());
                current = new Line();
                spaces.clear();
                endedWithNewline = true;
                continue;
            }
            endedWithNewline = false;
            if (token.space()) {
                spaces.add(token);
                continue;
            }
            double wordWidth = width(token.value(), token.bold());
            double spaceWidth = spaces.stream().mapToDouble(part -> width(part.value(), part.bold())).sum();
            if (current.width > 0 && current.width + spaceWidth + wordWidth > maxWidth) {
                lines.add(current.result());
                current = new Line();
            } else if (current.width > 0) {
                for (Token space : spaces) current.add(space, space.value(), width(space.value(), space.bold()));
            }
            spaces.clear();
            if (wordWidth <= maxWidth - current.width) {
                current.add(token, token.value(), wordWidth);
                continue;
            }
            if (current.width > 0) {
                lines.add(current.result());
                current = new Line();
            }
            StringBuilder part = new StringBuilder();
            for (int offset = 0; offset < token.value().length();) {
                int codepoint = token.value().codePointAt(offset);
                String character = new String(Character.toChars(codepoint));
                double candidate = width(part + character, token.bold());
                if (candidate > maxWidth && !part.isEmpty()) {
                    current.add(token, part.toString(), width(part.toString(), token.bold()));
                    lines.add(current.result());
                    current = new Line();
                    part.setLength(0);
                    candidate = width(character, token.bold());
                }
                if (candidate > maxWidth) throw new IllegalArgumentException("Slot is too narrow for a character");
                part.append(character);
                offset += Character.charCount(codepoint);
            }
            if (!part.isEmpty()) current.add(token, part.toString(), width(part.toString(), token.bold()));
        }
        if (current.width > 0 || lines.isEmpty() || endedWithNewline) lines.add(current.result());
        return lines;
    }

    /** Wraps a component to a slot and shortens overflow with an ellipsis.
     * @param value text to fit
     * @param slot target slot
     * @return lines that fit the slot
     */
    public List<Rhea.SlotLine> fit(Component value, RheaTemplate.Slot slot) {
        int maxLines = slot.height() / slot.lineAdvance();
        if (maxLines < 1) throw new IllegalArgumentException("Slot " + slot.id() + " is too short for text");
        List<Rhea.SlotLine> lines = wrap(value, slot.width());
        if (lines.size() <= maxLines) return lines;
        double ellipsisWidth = width("…", false);
        int remainingWidth = (int) Math.floor(slot.width() - ellipsisWidth);
        if (remainingWidth < 1) throw new IllegalArgumentException("Slot " + slot.id() + " is too narrow for an ellipsis");
        Rhea.SlotLine last = wrap(lines.get(maxLines - 1).content(), remainingWidth).getFirst();
        List<Rhea.SlotLine> fitted = new ArrayList<>(lines.subList(0, maxLines - 1));
        fitted.add(new Rhea.SlotLine(last.content().append(Component.text("…")), last.width() + ellipsisWidth));
        return fitted;
    }

    private static void collect(Component value, List<TextComponent> parents, boolean inheritedBold, List<Token> tokens) {
        if (!(value instanceof TextComponent text))
            throw new IllegalArgumentException("Live text supports text components only; translate other components first");
        boolean bold = switch (text.decoration(TextDecoration.BOLD)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> inheritedBold;
        };
        String content = text.content();
        int start = 0;
        for (int offset = 0; offset < content.length(); offset++) {
            char character = content.charAt(offset);
            if (character != '\n' && !Character.isWhitespace(character)) continue;
            if (start < offset) tokens.add(new Token(content.substring(start, offset), text, parents, bold, false, false));
            if (character == '\n') tokens.add(new Token("", text, parents, bold, false, true));
            else tokens.add(new Token(" ", text, parents, bold, true, false));
            start = offset + 1;
        }
        if (start < content.length()) tokens.add(new Token(content.substring(start), text, parents, bold, false, false));
        List<TextComponent> childParents = new ArrayList<>(parents);
        childParents.add(text);
        for (Component child : text.children()) collect(child, childParents, bold, tokens);
    }

    private record Token(String value, TextComponent source, List<TextComponent> parents,
                         boolean bold, boolean space, boolean newline) {
        Component fragment(String content) {
            Component result = source.content(content).children(List.of());
            for (int i = parents.size() - 1; i >= 0; i--)
                result = parents.get(i).content("").children(List.of(result));
            return result;
        }
    }

    private static final class Line {
        private Component content = Component.empty();
        private double width;

        void add(Token token, String value, double advance) {
            content = content.append(token.fragment(value));
            width += advance;
        }

        Rhea.SlotLine result() { return new Rhea.SlotLine(content, width); }
    }
}
