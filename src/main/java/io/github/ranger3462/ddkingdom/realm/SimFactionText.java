package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ranger3462.ddkingdom.sim.Personality;
import io.github.ranger3462.ddkingdom.sim.Rules;
import io.github.ranger3462.ddkingdom.sim.SimMap;

/**
 * How a faction plays, in plain words for the realm menu's cards: from the simulator's rules (its personality in
 * rules.json, docs/rules/kingdom-behaviour.txt) and its setup on the server's map, so the menu says what the realm
 * actually does.
 */
public final class SimFactionText {
    private static Rules rules;
    private static Map<String, SimMap.KingdomSetup> setups;

    private SimFactionText() {}

    private static synchronized void load() {
        if (rules != null) {
            return;
        }
        rules = Rules.defaults();
        setups = new java.util.LinkedHashMap<>();
        SimMap.named(SimRealm.DEFAULT_MAP).kingdoms.forEach(k -> setups.put(k.id, k));
        if (rules.minor != null) {
            rules.minor.factions.values().forEach(f -> setups.put(f.kingdom.id, f.kingdom));
        }
    }

    /** A realm faction's card text ("Grows fast. Eager to fight: attacks ..."), or empty for one the simulator doesn't know. */
    public static String about(String faction) {
        load();
        String kid = RealmSimTurns.kid(faction);
        SimMap.KingdomSetup k = setups.get(kid);
        if (k == null) {
            return "";
        }
        Personality p = Personality.of(rules, k.ai);
        boolean minor = rules.minor != null && rules.minor.factions.values().stream().anyMatch(f -> f.kingdom.id.equals(kid));
        List<String> parts = new ArrayList<>();
        if (minor) {
            Rules.MinorFaction f = rules.minor.factions.values().stream().filter(m -> m.kingdom.id.equals(kid)).findFirst().orElseThrow();
            parts.add("Comes and goes: holds at most " + f.maxNodes + " places, and returns some rounds after it's driven out.");
        } else {
            parts.add(switch (k.growth) {
                case "fast" -> "Grows fast.";
                case "slow" -> "Grows slowly.";
                default -> "Grows steadily.";
            });
        }
        String whom = switch (p.targets) {
            case "avoidMonsters" -> "the Hearthbound and unheld land, other monsters only when they're weak";
            case "playerOnly" -> "only the Hearthbound";
            case "home" -> "only what lies in its homeland";
            case "raze" -> "only its homeland and its old foes";
            default -> "anyone";
        };
        String keen = p.aggr >= 1.75 ? "Eager to fight: a" : p.aggr <= 1.0 ? "Cautious: a" : "A";
        String odds = p.minWin > 0 ? " at " + Math.round(p.minWin * 100) + "%+ odds" : "";
        parts.add(keen + "ttacks" + odds + (p.warOnly > 0 ? ", whatever the cost" : "") + ", going for " + whom + ".");
        if (p.raid >= 2) {
            parts.add("Raids far more than it conquers.");
        } else if (p.raid <= 0) {
            parts.add("Never raids.");
        }
        if (k.strength > 1) {
            parts.add("Hits " + Math.round((k.strength - 1) * 100) + "% harder.");
        }
        if (k.defStrength != null && k.defStrength > 1) {
            parts.add("Defends " + Math.round((k.defStrength - 1) * 100) + "% harder.");
        }
        if (k.raiseDead > 0) {
            parts.add("Raises " + Math.round(k.raiseDead * 100) + "% of the fallen after every battle.");
        }
        if (p.secureNew > 0) {
            parts.add("Secures what it takes before moving on.");
        }
        if (p.homeRadius > 0) {
            parts.add("Stays within " + Math.round(p.homeRadius) + " links of home.");
        }
        if (p.counter > 0) {
            parts.add("Strikes back at whoever attacks it.");
        }
        if (p.raze > 0 || k.razes) {
            parts.add("Razes what it takes.");
        }
        if (p.developTier > 0) {
            parts.add("Builds its land up to tier " + Math.round(p.developTier) + " before expanding.");
        }
        if (k.upkeepMult > 1) {
            parts.add("Its army costs " + (k.upkeepMult == 1.5 ? "half again as much" : k.upkeepMult + "x as much") + " to keep.");
        }
        if (k.brood && rules.brood != null && rules.brood.enabled) {
            parts.add("Hoards gold for a dragon: when the hoard is full, the brood wakes and fights twice as hard.");
        }
        return String.join(" ", parts);
    }
}
