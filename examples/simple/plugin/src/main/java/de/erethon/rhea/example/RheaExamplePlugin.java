package de.erethon.rhea.example;

import de.erethon.rhea.Rhea;
import de.erethon.rhea.RheaTemplate;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RheaExamplePlugin extends JavaPlugin {
    private Rhea rhea;
    private RheaTemplate journal;
    private RheaTemplate guide;

    @Override
    public void onEnable() {
        rhea = new Rhea(this);
        journal = RheaTemplate.read(this, "rhea/main.json");
        guide = RheaTemplate.read(this, "rhea/guide.json");

        Objects.requireNonNull(getCommand("rheademo")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is only available in-game.");
                return true;
            }
            show(player, false);
            return true;
        });
    }

    private void show(Player player, boolean onGuide) {
        String greeting = onGuide ? "Keep exploring, " + player.getName() + "."
                : "Hello, " + player.getName() + ".";
        rhea.dialog(onGuide ? guide : journal, Component.text(onGuide ? "Guide" : "Journal"), Component.text("Close"))
                .slot("greeting", Component.text(greeting))
                .action("switch:page", clicked -> show(clicked, !onGuide))
                .show(player);
    }
}
