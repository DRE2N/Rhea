package de.erethon.rhea;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Shows compiled Rhea pages as Paper dialogs. Keep one instance per plugin. */
public final class Rhea {
    private static final char MINUS_ONE = '\uF000';
    private static final char MINUS_HALF = '\uF014';
    private static final char PLUS_HALF = '\uF015';
    private static final char MINUS_QUARTER = '\uF016';
    private static final char PLUS_QUARTER = '\uF017';
    private static final int WIDTH_GUARD = 8;
    private static final int[] NEGATIVE_WIDTHS = {512, 256, 128, 64, 32, 16, 8, 4, 2, 1};
    private static final char[] NEGATIVE_GLYPHS = {'\uF001', '\uF002', '\uF003', '\uF004', '\uF005',
            '\uF006', '\uF007', '\uF008', '\uF009', '\uF000'};
    private static final int[] POSITIVE_WIDTHS = {512, 256, 128, 64, 32, 16, 8, 4, 2, 1};
    private static final char[] POSITIVE_GLYPHS = {'\uF013', '\uF012', '\uF011', '\uF010', '\uF00F',
            '\uF00E', '\uF00D', '\uF00C', '\uF00B', '\uF00A'};

    private final JavaPlugin plugin;
    private final RheaText text;
    private final Map<String, RheaText> slotFonts = new HashMap<>();

    /** Uses the plugin scheduler for click callbacks and the close button.
     * @param plugin owning plugin
     */
    public Rhea(JavaPlugin plugin) { this(plugin, null); }

    /** Uses a bundled body font to measure and wrap live text automatically.
     * @param plugin owning plugin
     * @param text body font matching the compiled template
     */
    public Rhea(JavaPlugin plugin, RheaText text) {
        this.plugin = Objects.requireNonNull(plugin);
        this.text = text;
    }

    /**
     * One live-text line for the lower-level {@code show} method. {@code width}
     * is its client-font advance in pixels, measured to a quarter pixel.
     * @param content text to render
     * @param width measured advance in pixels
     */
    public record SlotLine(Component content, double width) {
        /** Checks the measured width.
         * @param content text to render
         * @param width measured advance in pixels
         */
        public SlotLine {
            Objects.requireNonNull(content);
            if (!Double.isFinite(width) || width < 0 || width * 4 != Math.rint(width * 4))
                throw new IllegalArgumentException("Slot line width must be a nonnegative multiple of a quarter pixel");
        }
    }

    /** Starts a dialog with fluent bindings for live text and actions. */
    public DialogBuilder dialog(RheaTemplate template, Component title, Component closeLabel) {
        return new DialogBuilder(template, title, closeLabel);
    }

    /** Returns the slot's compiled font for measuring or paginating text. */
    public RheaText text(RheaTemplate template, String slotId) {
        return fontFor(template, template.slot(slotId));
    }

    /** Bind the dynamic parts of one dialog before showing it. */
    public final class DialogBuilder {
        private final RheaTemplate template;
        private final Component title;
        private final Component closeLabel;
        private final Map<String, Component> textSlots = new HashMap<>();
        private final Map<String, List<SlotLine>> lineSlots = new HashMap<>();
        private final Map<String, Consumer<Player>> actions = new HashMap<>();
        private final Map<String, Component> hovers = new HashMap<>();

        private DialogBuilder(RheaTemplate template, Component title, Component closeLabel) {
            this.template = Objects.requireNonNull(template);
            this.title = Objects.requireNonNull(title);
            this.closeLabel = Objects.requireNonNull(closeLabel);
        }

        /** Fills a live slot, measuring and wrapping with its CSS font. */
        public DialogBuilder slot(String id, Component value) {
            template.slot(id);
            textSlots.put(id, Objects.requireNonNull(value));
            return this;
        }

        /** Supplies premeasured lines, useful for pagination. */
        public DialogBuilder lines(String id, List<SlotLine> value) {
            template.slot(id);
            lineSlots.put(id, List.copyOf(value));
            return this;
        }

        /** Binds a click callback to an authored action ID. */
        public DialogBuilder action(String id, Consumer<Player> callback) {
            if (!template.actions().contains(id)) throw new IllegalArgumentException("Unknown Rhea action: " + id);
            actions.put(id, Objects.requireNonNull(callback));
            return this;
        }

        /** Adds hover text to an authored action ID. */
        public DialogBuilder hover(String id, Component value) {
            if (!template.actions().contains(id)) throw new IllegalArgumentException("Unknown Rhea action: " + id);
            hovers.put(id, Objects.requireNonNull(value));
            return this;
        }

        /** Opens the completed dialog for a player. */
        public void show(Player player) {
            Map<String, List<SlotLine>> fitted = new HashMap<>(lineSlots);
            for (var entry : textSlots.entrySet()) {
                RheaTemplate.Slot slot = template.slot(entry.getKey());
                if (fitted.putIfAbsent(entry.getKey(), fontFor(template, slot).fit(entry.getValue(), slot)) != null)
                    throw new IllegalArgumentException("Slot " + slot.id() + " has both text and lines");
            }
            Rhea.this.show(player, template, title, closeLabel, fitted, actions, hovers);
        }
    }

    /** Fits live components into their authored slots before opening the dialog.
     * Overflow is shortened with an ellipsis. For pagination, use {@link RheaText#wrap(Component, int)}
     * and the line-based {@link #show(Player, RheaTemplate, Component, Component, Map, Map)}.
     * @param player viewer
     * @param template compiled page
     * @param title native dialog title
     * @param closeLabel native close button label
     * @param slots live text by slot ID
     * @param actions click handlers by action ID
     */
    public void showText(Player player, RheaTemplate template, Component title, Component closeLabel,
                         Map<String, Component> slots, Map<String, Consumer<Player>> actions) {
        showText(player, template, title, closeLabel, slots, actions, Map.of());
    }

    /** Fits live components into slots, with optional action hover text.
     * @param player viewer
     * @param template compiled page
     * @param title native dialog title
     * @param closeLabel native close button label
     * @param slots live text by slot ID
     * @param actions click handlers by action ID
     * @param hovers hover text by action ID
     */
    public void showText(Player player, RheaTemplate template, Component title, Component closeLabel,
                         Map<String, Component> slots, Map<String, Consumer<Player>> actions,
                         Map<String, Component> hovers) {
        Map<String, List<SlotLine>> lines = new HashMap<>();
        for (var entry : slots.entrySet()) {
            RheaTemplate.Slot slot = template.slot(entry.getKey());
            lines.put(entry.getKey(), fontFor(template, slot).fit(entry.getValue(), slot));
        }
        show(player, template, title, closeLabel, lines, actions, hovers);
    }

    private RheaText fontFor(RheaTemplate template, RheaTemplate.Slot slot) {
        if (slot.fontFile() == null) {
            if (text == null) throw new IllegalStateException("This template needs a RheaText or compiled slot fonts");
            return text;
        }
        if (template.resourceBase() == null)
            throw new IllegalStateException("Load templates with RheaTemplate.read(plugin, path) for automatic fonts");
        String path = template.resourceBase() + "fonts/" + slot.fontFile();
        return slotFonts.computeIfAbsent(path, ignored ->
                new RheaText(plugin.getResource(path), (float) slot.fontSize(), slot.fontOversample()));
    }

    /** Shows a page with live slots and click handlers. Unbound actions do nothing.
     * @param player viewer
     * @param template compiled page
     * @param title native dialog title
     * @param closeLabel native close button label
     * @param slots live lines by slot ID
     * @param actions click handlers by action ID
     */
    public void show(Player player, RheaTemplate template, Component title, Component closeLabel,
                     Map<String, List<SlotLine>> slots, Map<String, Consumer<Player>> actions) {
        show(player, template, title, closeLabel, slots, actions, Map.of());
    }

    /**
     * Shows a page with click handlers and hover text keyed by authored action ID.
     * Hover text also works when an action has no click handler.
     * @param player viewer
     * @param template compiled page
     * @param title native dialog title
     * @param closeLabel native close button label
     * @param slots live lines by slot ID
     * @param actions click handlers by action ID
     * @param hovers hover text by action ID
     */
    public void show(Player player, RheaTemplate template, Component title, Component closeLabel,
                     Map<String, List<SlotLine>> slots, Map<String, Consumer<Player>> actions,
                     Map<String, Component> hovers) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(template);
        Key uiFont = Key.key(template.font());
        Map<Integer, List<Component>> overlays = new HashMap<>();
        for (var entry : slots.entrySet()) {
            RheaTemplate.Slot slot = template.slot(entry.getKey());
            int maxLines = slot.height() / slot.lineAdvance();
            if (entry.getValue().size() > maxLines) throw new IllegalArgumentException("Slot " + slot.id() + " exceeds " + maxLines + " lines");
            for (int i = 0; i < entry.getValue().size(); i++) {
                // Later bitmap rows cover earlier text. Draw downward-shifted
                // text on a later row, then offset its font back to the same pixels.
                int rowAdvance = Math.max(0, Math.ceilDiv(slot.fontShift(), 9));
                int renderShift = slot.fontShift() - rowAdvance * 9;
                int row = (slot.y() + 7 + i * slot.lineAdvance()) / 9 + rowAdvance;
                if (row >= template.rows().size()) throw new IllegalArgumentException("Slot line outside page");
                SlotLine slotLine = entry.getValue().get(i);
                if (slotLine.width() > slot.width()) throw new IllegalArgumentException("Slot line exceeds width of " + slot.id());
                String slotFontName = slot.font() == null ? "body" : slot.font();
                Key slotFont = renderShift == 0 ? Key.key(template.namespace(), slotFontName) : Key.key(template.namespace(),
                        slotFontName + "_shift_" + (renderShift > 0 ? "p" + renderShift : "m" + -renderShift));
                Component content = slotLine.content().font(slotFont);
                if (slot.action() != null) content = interactive(content, slot.action(), player, actions, hovers);
                double offset = switch (slot.align() == null ? "left" : slot.align()) {
                    case "center" -> Math.floor((slot.width() - slotLine.width()) * 2) / 4;
                    case "right" -> slot.width() - slotLine.width();
                    default -> 0;
                };
                Component overlay = Component.text(spaces(-template.width()) + spaces(slot.x() + offset), NamedTextColor.WHITE)
                        .font(uiFont).append(content)
                        .append(Component.text(spaces(template.width() - slot.x() - offset - slotLine.width())).font(uiFont));
                overlays.computeIfAbsent(row, ignored -> new ArrayList<>()).add(overlay);
            }
        }
        Component body = Component.empty().shadowColor(ShadowColor.none());
        for (int row = 0; row < template.rows().size(); row++) {
            Component line = Component.empty();
            // FocusableTextWidget measures its height by splitting at the widest
            // rendered line, not at the plain-message width. Give one line a
            // symmetric invisible margin so its measured width includes room
            // for transient advances before our negative spacing glyphs.
            if (row == 0) line = line.append(Component.text(spaces(WIDTH_GUARD)).font(uiFont));
            for (RheaTemplate.Segment segment : template.rows().get(row)) {
                Component piece = Component.text(segment.glyph(), NamedTextColor.WHITE).font(uiFont);
                if (segment.action() != null) piece = interactive(piece, segment.action(), player, actions, hovers);
                line = line.append(piece).append(Component.text(MINUS_ONE).font(uiFont));
            }
            for (Component overlay : overlays.getOrDefault(row, List.of())) line = line.append(overlay);
            if (row == 0) line = line.append(Component.text(spaces(WIDTH_GUARD)).font(uiFont));
            body = body.append(line);
            if (row + 1 < template.rows().size()) body = body.append(Component.newline());
        }
        Component bodyComponent = body;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        // The client reserves eight pixels of internal padding and centers each text line.
                        .body(List.of(DialogBody.plainMessage(bodyComponent, Math.min(1024, template.width() + 24))))
                        .build())
                .type(DialogType.notice(ActionButton.builder(closeLabel).width(120)
                        .action(DialogAction.staticAction(ClickEvent.callback(audience -> {
                            if (audience instanceof Player clicked && clicked.getUniqueId().equals(player.getUniqueId())) {
                                Bukkit.getScheduler().runTask(plugin, clicked::closeDialog);
                            }
                        }))).build())));
        player.showDialog(dialog);
    }

    private Component interactive(Component component, String action, Player player,
                                  Map<String, Consumer<Player>> actions, Map<String, Component> hovers) {
        Consumer<Player> callback = actions.get(action);
        if (callback != null) component = component.clickEvent(ClickEvent.callback(audience -> {
            if (audience instanceof Player clicked && clicked.getUniqueId().equals(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(clicked));
            }
        }));
        Component hover = hovers.get(action);
        if (hover != null) component = component.hoverEvent(HoverEvent.showText(hover));
        return component;
    }

    private static String spaces(double pixels) {
        if (!Double.isFinite(pixels) || pixels * 4 != Math.rint(pixels * 4))
            throw new IllegalArgumentException("Spacing must be a multiple of a quarter pixel");
        if (pixels == 0) return "";
        int remaining = (int) Math.floor(Math.abs(pixels));
        int[] widths = pixels < 0 ? NEGATIVE_WIDTHS : POSITIVE_WIDTHS;
        char[] glyphs = pixels < 0 ? NEGATIVE_GLYPHS : POSITIVE_GLYPHS;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            while (remaining >= widths[i]) { result.append(glyphs[i]); remaining -= widths[i]; }
        }
        int quarterRemainder = ((int) Math.rint(Math.abs(pixels) * 4)) % 4;
        if (quarterRemainder >= 2) result.append(pixels < 0 ? MINUS_HALF : PLUS_HALF);
        if ((quarterRemainder & 1) != 0) result.append(pixels < 0 ? MINUS_QUARTER : PLUS_QUARTER);
        return result.toString();
    }
}
