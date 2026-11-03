package dev.twitchchaos.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> Client: aktueller Zustand (Einstellungen + Twitch-Status) als JSON; open=true oeffnet das Menue. */
public record StatePayload(boolean open, String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StatePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("twitchchaos", "state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StatePayload::open,
            ByteBufCodecs.STRING_UTF8, StatePayload::json,
            StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
