package de.erethon.rhea;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RheaTextTest {
    private static RheaText font() throws IOException {
        return new RheaText(Files.newInputStream(Path.of("examples/simple/assets/AlegreyaSans-Regular.ttf")), 15, 4);
    }

    @Test
    void wrapsStyledLinksWithoutLosingWords() throws IOException {
        RheaText font = font();
        Component article = Component.text("Read the ")
                .append(Component.text("field guide", TextColor.color(0xA9D8D7)).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl("https://example.org")))
                .append(Component.text(" before heading out."));
        List<Rhea.SlotLine> lines = font.wrap(article, 74);

        assertTrue(lines.size() > 1);
        assertTrue(lines.stream().allMatch(line -> line.width() <= 74));
        assertEquals("Readthefieldguidebeforeheadingout.",
                lines.stream().map(line -> plain(line.content())).reduce("", String::concat).replace(" ", ""));
        assertTrue(lines.stream().anyMatch(line -> hasClick(line.content())));
    }

    @Test
    void shortensTextToSlotHeight() throws IOException {
        RheaText font = font();
        var slot = new RheaTemplate.Slot("greeting", 0, 0, 80, 18, null, "left", 0);
        List<Rhea.SlotLine> lines = font.fit(Component.text("A long greeting for every traveller who opens the journal"), slot);

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().width() <= slot.width());
        assertTrue(plain(lines.getFirst().content()).endsWith("…"));
    }

    private static String plain(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof TextComponent text) result.append(text.content());
        for (Component child : component.children()) result.append(plain(child));
        return result.toString();
    }

    private static boolean hasClick(Component component) {
        if (component.clickEvent() != null) return true;
        return component.children().stream().anyMatch(RheaTextTest::hasClick);
    }
}
