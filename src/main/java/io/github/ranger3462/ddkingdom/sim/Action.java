package io.github.ranger3462.ddkingdom.sim;

/**
 * One action a kingdom takes. Which fields matter depends on the {@link Type}: {@code from}, {@code target} and
 * {@code troops} for attacks (and {@code resource} for a raid), {@code from}, {@code to} and {@code amount} to send
 * troops, {@code node} for what's hired into, fortified ({@code steps}), upgraded or repaired, {@code batches} to hire,
 * {@code food} a turn to import.
 */
public record Action(Type type, String from, String to, String target, int troops, int amount, int batches, String node, int steps,
        String resource, int food) {
    public enum Type {
        REINFORCE_BORDER("reinforce_border", "Reinforce border"),
        SEND("send", "Send reinforcements"),
        CAPTURE("capture", "Capture node"),
        RAID("raid", "Raid"),
        HIRE("hire", "Hire soldiers"),
        PRODUCE("produce", "Produce resources"),
        FORTIFY("fortify", "Fortify a node"),
        UPGRADE("upgrade", "Upgrade a node"),
        HEROIC("heroic", "Heroic conquest"),
        REPAIR("repair", "Repair node"),
        IMPORT_FOOD("import_food", "Import food");

        /** As the simulator names it. */
        public final String id;
        public final String label;

        Type(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** An attack: Capture Node, Raid or Heroic Conquest. */
        public boolean attack() {
            return this == CAPTURE || this == RAID || this == HEROIC;
        }
    }

    public static Action of(Type type) {
        return new Action(type, null, null, null, 0, 0, 0, null, 0, null, 0);
    }

    public static Action capture(String from, String target, int troops) {
        return new Action(Type.CAPTURE, from, null, target, troops, 0, 0, null, 0, null, 0);
    }

    public static Action heroic(String from, String target, int troops) {
        return new Action(Type.HEROIC, from, null, target, troops, 0, 0, null, 0, null, 0);
    }

    /** {@code resource} is "food", "gold" or "res". */
    public static Action raid(String from, String target, int troops, String resource) {
        return new Action(Type.RAID, from, null, target, troops, 0, 0, null, 0, resource, 0);
    }

    public static Action send(String from, String to, int amount) {
        return new Action(Type.SEND, from, to, null, 0, amount, 0, null, 0, null, 0);
    }

    public static Action hire(String node, int batches) {
        return new Action(Type.HIRE, null, null, null, 0, 0, batches, node, 0, null, 0);
    }

    public static Action fortify(String node, int steps) {
        return new Action(Type.FORTIFY, null, null, null, 0, 0, 0, node, steps, null, 0);
    }

    public static Action upgrade(String node) {
        return new Action(Type.UPGRADE, null, null, null, 0, 0, 0, node, 0, null, 0);
    }

    public static Action repair(String node) {
        return new Action(Type.REPAIR, null, null, null, 0, 0, 0, node, 0, null, 0);
    }

    public static Action importFood(int food) {
        return new Action(Type.IMPORT_FOOD, null, null, null, 0, 0, 0, null, 0, null, food);
    }
}
