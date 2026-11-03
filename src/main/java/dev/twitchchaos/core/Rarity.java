package dev.twitchchaos.core;

public enum Rarity {
    NORMAL("Normal"),
    RARE("Selten"),
    SPECIAL("Besonders");

    public final String label;

    Rarity(String label) {
        this.label = label;
    }
}
