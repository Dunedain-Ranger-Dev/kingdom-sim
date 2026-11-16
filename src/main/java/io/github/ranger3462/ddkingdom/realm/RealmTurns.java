package io.github.ranger3462.ddkingdom.realm;

import java.util.List;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The realm's clock (DD-95, docs/design/realm-cutover.md): every second, the realm plays the rounds that are due by the
 * simulator's rules ({@link RealmSimTurns}), a round every {@code realmRoundMinutes} on a real-time clock that keeps
 * counting while the server is off, and tells every player online the news. Also what the menu and commands ask of
 * the map: who holds what, and how strong.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmTurns {
    private RealmTurns() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) {
            Realm realm = Realm.get(event.getServer());
            RealmSimTurns.tick(event.getServer(), realm, System.currentTimeMillis());
            tellNews(event.getServer(), realm);
        }
    }

    /** Tells every player online the realm's news. */
    public static void tellNews(MinecraftServer server, Realm realm) {
        for (Realm.LogEntry entry : realm.takeNews()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(entry.text()).withStyle(ChatFormatting.RED), false);
        }
    }

    /** A round's length in real time. */
    public static long turnMillis() {
        return KingdomConfig.REALM_TURN_MINUTES.get() * 60_000L;
    }

    /** A nation's strongholds. */
    public static List<RealmNode> strongholds(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.STRONGHOLD && nation.equals(node.owner())).toList();
    }

    /** The towns a nation holds. */
    public static List<RealmNode> towns(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.TOWN && nation.equals(node.owner())).toList();
    }

    /** A nation's whole military: the troops in everything it holds. */
    public static int military(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> nation.equals(node.owner())).mapToInt(node -> node.stat("military")).sum();
    }
}
