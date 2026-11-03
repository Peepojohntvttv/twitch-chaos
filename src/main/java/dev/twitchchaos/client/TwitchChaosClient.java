package dev.twitchchaos.client;

import dev.twitchchaos.net.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Client-Einstieg: empfaengt Zustandspakete und oeffnet bei Bedarf das Menue (/twitchchaos gui). */
public class TwitchChaosClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> {
            Minecraft mc = context.client();
            mc.execute(() -> {
                ClientState.update(payload.json());
                if (payload.open()) {
                    mc.gui.setScreen(new ChaosScreen());
                } else if (mc.gui.screen() instanceof ChaosScreen screen) {
                    screen.onStateChanged();
                }
            });
        });
    }
}
