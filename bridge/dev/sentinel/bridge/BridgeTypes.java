package dev.sentinel.bridge;

import dev.sentinel.anticheat.model.CheckType;
import java.util.Locale;

final class BridgeTypes {
    private BridgeTypes() {
    }

    static Mapping map(String string) {
        if (string == null) {
            return null;
        }
        String string2 = string.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        // Preserve exact native check ids; the old substring map converted KillAura into Aim.
        for (CheckType type : CheckType.values()) {
            if (string2.equals(type.name().toLowerCase(Locale.ROOT).replace("_", ""))) {
                Family family = switch (type) {
                    case SPEED, FLIGHT, BAD_PITCH, TIMER -> Family.MOVEMENT;
                    case FAST_BREAK, FAST_PLACE -> Family.WORLD;
                    default -> Family.COMBAT;
                };
                return new Mapping(family, type);
            }
        }
        if (BridgeTypes.contains(string2, "freecam", "remoteinteract", "remoteaction", "invalidinteract", "ghosthand")) {
            return new Mapping(Family.WORLD, CheckType.REACH);
        }
        if (BridgeTypes.contains(string2, "reach", "hitbox", "wallhit", "throughwall", "raytrace")) {
            return new Mapping(Family.COMBAT, CheckType.REACH);
        }
        if (BridgeTypes.contains(string2, "multitarget", "multipleentities", "multiaura")) {
            return new Mapping(Family.COMBAT, CheckType.MULTI_TARGET);
        }
        if (BridgeTypes.contains(string2, "autoclick", "clicker", "clickspam", "packetspam", "cps")) {
            return new Mapping(Family.COMBAT, CheckType.AUTO_CLICKER);
        }
        if (BridgeTypes.contains(string2, "badpitch", "pitchlimit", "headroll")) {
            return new Mapping(Family.COMBAT, CheckType.BAD_PITCH);
        }
        if (BridgeTypes.contains(string2, "aim", "aura", "killaura", "rotation", "snap", "spin", "trigger", "combat")) {
            return new Mapping(Family.COMBAT, CheckType.AIM);
        }
        if (BridgeTypes.contains(string2, "timer", "balance")) {
            return new Mapping(Family.MOVEMENT, CheckType.TIMER);
        }
        if (BridgeTypes.contains(string2, "flight", "fly", "airjump", "elytra", "groundspoof", "hover", "ascension")) {
            return new Mapping(Family.MOVEMENT, CheckType.FLIGHT);
        }
        if (BridgeTypes.contains(string2, "speed", "velocity", "motion", "movement", "simulation", "prediction", "jesus", "waterwalk", "noslow", "noweb", "knockback", "phase", "blink", "bunnyhop", "climb", "step", "strafe", "swim", "yport", "sprint", "spider")) {
            return new Mapping(Family.MOVEMENT, CheckType.SPEED);
        }
        if (BridgeTypes.contains(string2, "scaffold", "fastplace", "airplace", "autoplace", "tower")) {
            return new Mapping(Family.WORLD, CheckType.FAST_PLACE);
        }
        if (BridgeTypes.contains(string2, "fastbreak", "breakpattern", "nuker", "instabreak", "fastmine")) {
            return new Mapping(Family.WORLD, CheckType.FAST_BREAK);
        }
        return null;
    }

    private static boolean contains(String string, String ... stringArray) {
        for (String string2 : stringArray) {
            if (!string.contains(string2)) continue;
            return true;
        }
        return false;
    }

    record Mapping(Family family, CheckType type) {
    }

    static enum Family {
        MOVEMENT,
        COMBAT,
        WORLD;

    }

    record Observation(long time, Source source, Family family, CheckType type, double confidence, String check, String details) {
    }

    static enum Source {
        SENTINEL("SentinelAC"),
        GRIM("GrimAC"),
        LMV("LmvAC"),
        WATCHCAT("WatchCat"),
        WALRUS("WalrusAC"),
        GUARD("SentinelBridge Guard");

        private final String display;

        private Source(String string2) {
            this.display = string2;
        }

        String display() {
            return this.display;
        }
    }
}

