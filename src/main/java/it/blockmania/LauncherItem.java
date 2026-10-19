package it.blockmania;

import org.bukkit.Material;

import java.util.List;

/** Un oggetto lanciapalle definito nel config. */
public record LauncherItem(
        String id,
        Material material,
        String name,
        List<String> lore,
        Integer customModelData,
        double speed,
        double cooldownSeconds,
        String ammoType) {
}
