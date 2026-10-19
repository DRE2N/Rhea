package de.erethon.rhea;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/** A page descriptor produced by the Rhea compiler.
 * @param id page ID
 * @param namespace resource-pack namespace
 * @param font bitmap font key
 * @param width canvas width in pixels
 * @param height canvas height in pixels
 * @param rows bitmap rows
 * @param slots live-text regions
 * @param actions authored action IDs
 * @param resourceBase location of generated font files in the plugin JAR
 */
public record RheaTemplate(String id, String namespace, String font, int width, int height,
                           List<List<Segment>> rows, List<Slot> slots, List<String> actions, String resourceBase) {
    private static final Gson GSON = new Gson();

    public RheaTemplate(String id, String namespace, String font, int width, int height,
                        List<List<Segment>> rows, List<Slot> slots, List<String> actions) {
        this(id, namespace, font, width, height, rows, slots, actions, null);
    }

    /** One bitmap glyph and its optional action ID.
     * @param glyph bitmap character
     * @param action action ID, or null
     */
    public record Segment(String glyph, String action) { }
    /** A bounded live-text region. {@code align} is left, center, or right.
     * @param id slot ID
     * @param x left position in pixels
     * @param y top position in pixels
     * @param width region width in pixels
     * @param height region height in pixels
     * @param action containing action ID, or null
     * @param align text alignment
     * @param fontShift measured vertical text shift in pixels
     * @param font compiled resource-pack font name
     * @param fontFile local font file bundled beside the template
     * @param fontSize CSS font size in pixels
     * @param fontOversample Minecraft font sampling factor
     * @param lineHeight CSS line height in pixels
     */
    public record Slot(String id, int x, int y, int width, int height, String action, String align, int fontShift,
                       String font, String fontFile, double fontSize, int fontOversample, int lineHeight) {
        public Slot(String id, int x, int y, int width, int height, String action, String align, int fontShift) {
            this(id, x, y, width, height, action, align, fontShift, null, null, 0, 0, 0);
        }
        /** Pixels between live text lines. */
        public int lineAdvance() { return lineHeight > 0 ? lineHeight : 18; }
    }

    /** Loads a template and remembers where its generated font files sit in the plugin JAR. */
    public static RheaTemplate read(JavaPlugin plugin, String path) {
        if (path == null || !path.endsWith(".json")) throw new IllegalArgumentException("Expected a template JSON path");
        RheaTemplate page = read(plugin.getResource(path));
        return new RheaTemplate(page.id, page.namespace, page.font, page.width, page.height,
                page.rows, page.slots, page.actions, path.substring(0, path.lastIndexOf('/') + 1));
    }

    /** Reads a descriptor bundled in the plugin JAR.
     * @param stream descriptor input
     * @return compiled page
     */
    public static RheaTemplate read(InputStream stream) {
        if (stream == null) throw new IllegalArgumentException("Missing Rhea template");
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RheaTemplate template = GSON.fromJson(reader, RheaTemplate.class);
            if (template == null || template.id == null || template.namespace == null || template.font == null ||
                    template.rows == null || template.slots == null || template.actions == null ||
                    template.width < 1 || template.width > 1000 || template.height != template.rows.size() * 9) {
                throw new IllegalArgumentException("Invalid Rhea template");
            }
            return template;
        } catch (Exception ex) {
            throw new JsonParseException("Cannot read Rhea template", ex);
        }
    }

    /** Finds a live-text region by its authored ID.
     * @param id slot ID
     * @return matching slot
     */
    public Slot slot(String id) {
        return slots.stream().filter(slot -> slot.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Rhea slot: " + id));
    }
}
