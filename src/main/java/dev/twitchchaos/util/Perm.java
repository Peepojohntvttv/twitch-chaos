package dev.twitchchaos.util;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Berechtigungspruefung. Wer den Befehl /gamemode nutzen darf (Operator-Stufe 2), darf Twitch Chaos
 * verwalten. Im Einzelspieler-Modus darf der Spieler immer verwalten, auch ohne Cheats.
 * Diese Pruefung haengt nur an der Befehlsstruktur und ist dadurch unabhaengig von
 * Umbauten der Berechtigungs-API in neueren Minecraft-Versionen.
 */
public final class Perm {
    private Perm() {
    }

    public static boolean canAdmin(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return false;
        if (server.isSingleplayer()) return true;
        CommandNode<CommandSourceStack> node = server.getCommands().getDispatcher().getRoot().getChild("gamemode");
        return node != null && node.canUse(src);
    }
}
