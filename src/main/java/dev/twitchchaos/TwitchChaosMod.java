package dev.twitchchaos;

import dev.twitchchaos.command.ChaosCommand;
import dev.twitchchaos.config.ChaosConfig;
import dev.twitchchaos.core.ChaosManager;
import dev.twitchchaos.net.ServerNet;
import dev.twitchchaos.twitch.TwitchAuth;
import dev.twitchchaos.twitch.TwitchService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Gemeinsamer Einstiegspunkt (laeuft auf Client und Server). Twitch laeuft dort, wo der Server laeuft. */
public class TwitchChaosMod implements ModInitializer {
    public static final String ID = "twitchchaos";
    public static final Logger LOG = LoggerFactory.getLogger("TwitchChaos");

    public static ChaosConfig config;
    public static TwitchAuth auth;
    public static volatile MinecraftServer server;
    public static volatile ChaosManager manager;
    public static volatile TwitchService twitch;

    @Override
    public void onInitialize() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        config = ChaosConfig.load(dir.resolve("twitchchaos.json"));
        auth = new TwitchAuth(dir.resolve("twitchchaos_auth.json"));

        ServerNet.register();
        ChaosCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            manager = new ChaosManager(s, config);
            twitch = new TwitchService(config, auth,
                    // Twitch-Thread -> Server-Thread wechseln; die Ereignisse werden dort entdoppelt und begrenzt.
                    ev -> s.execute(() -> {
                        ChaosManager m = manager;
                        if (m != null) m.submit(ev);
                    }),
                    () -> s.execute(() -> ServerNet.broadcastState(s)));
            if (config.autoConnect && !config.clientId.isBlank()) twitch.connect();
            LOG.info("Twitch Chaos bereit. /twitchchaos help");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            TwitchService t = twitch;
            if (t != null) t.shutdown();
            twitch = null;
            manager = null;
            server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ChaosManager m = manager;
            if (m != null) m.tick();
        });
    }
}
