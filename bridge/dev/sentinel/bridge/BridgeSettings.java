package dev.sentinel.bridge;

import dev.sentinel.bridge.BridgeTypes;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

final class BridgeSettings {
    final long incidentWindowMs;
    final long duplicateWindowMs;
    final long verdictCooldownMs;
    final long joinGraceMs;
    final long teleportGraceMs;
    final long velocityGraceMs;
    final double minimumTps;
    final int maximumPing;
    final int minimumSources;
    final double minimumWeight;
    final double baseScore;
    final double maximumScore;
    final double maximumConfidence;
    final boolean debug;
    final boolean enforce;
    final Set<String> ignoredWorlds;
    final EnumMap<BridgeTypes.Source, Boolean> enabled = new EnumMap(BridgeTypes.Source.class);
    final EnumMap<BridgeTypes.Source, Double> weight = new EnumMap(BridgeTypes.Source.class);
    final boolean grimIgnoreExperimental;
    final double watchcatThreshold;
    final double watchcatResetThreshold;
    final double watchcatMinimumRise;
    final long watchcatRepeatMs;
    final double walrusThreshold;
    final int walrusMinimumDetections;
    final long walrusRepeatMs;
    final boolean punishmentsEnabled;
    final int consensusBanVerdicts;
    final long consensusBanWindowMs;
    final long punishmentCooldownMs;
    final int secondBanDays;
    final int thirdBanDays;
    final boolean ipBanEnabled;
    final boolean allowPrivateIpBan;
    final boolean externalCommandsEnabled;
    final String secondCommand;
    final String thirdCommand;
    final String fourthCommand;
    final boolean wallHitEnabled;
    final int wallHitBanViolations;
    final long wallHitBanWindowMs;
    final long wallHitCountCooldownMs;
    final double wallHitRayTolerance;
    final double wallHitMaximumDistance;
    final double wallHitSentinelScore;
    final double wallHitSentinelConfidence;
    final boolean mobCombatEnabled;
    final double mobCombatMaximumAngle;
    final int mobCombatRotationViolations;
    final long mobCombatRotationWindowMs;
    final long mobCombatCountCooldownMs;
    final int mobCombatMultiTargets;
    final long mobCombatMultiTargetWindowMs;
    final long mobCombatMultiTargetRepeatMs;
    final boolean freeCamEnabled;
    final boolean freeCamOcclusion;
    final long freeCamMinimumSeriesMs;
    final double interactionRangeTolerance;
    final int freeCamPunishViolations;
    final long freeCamPunishWindowMs;
    final long freeCamCountCooldownMs;
    final double freeCamMaximumBlockDistance;
    final double freeCamMaximumEntityDistance;
    final double freeCamRayTolerance;
    final boolean freeCamSkipPlaceOcclusion;
    final double freeCamSentinelScore;
    final double freeCamSentinelConfidence;
    final boolean unbanSyncEnabled;
    final Set<String> unbanPlayerCommands;
    final Set<String> unbanIpCommands;
    final String punishmentMessageTitle;
    final String punishmentMessageSeparator;
    final String punishmentMessageAppeal;

    private BridgeSettings(FileConfiguration fileConfiguration) {
        this.incidentWindowMs = BridgeSettings.positive(fileConfiguration.getLong("consensus.incident-window-ms", 15000L), 15000L);
        this.duplicateWindowMs = BridgeSettings.positive(fileConfiguration.getLong("consensus.duplicate-window-ms", 1000L), 1000L);
        this.verdictCooldownMs = BridgeSettings.positive(fileConfiguration.getLong("consensus.verdict-cooldown-ms", 2500L), 2500L);
        this.minimumSources = Math.max(2, fileConfiguration.getInt("consensus.minimum-sources", 2));
        this.minimumWeight = BridgeSettings.clamp(fileConfiguration.getDouble("consensus.minimum-weight", 1.3), 0.1, 10.0);
        this.baseScore = BridgeSettings.clamp(fileConfiguration.getDouble("consensus.sentinel-score", 5.0), 0.1, 20.0);
        this.maximumScore = BridgeSettings.clamp(fileConfiguration.getDouble("consensus.maximum-sentinel-score", 7.0), this.baseScore, 30.0);
        this.maximumConfidence = BridgeSettings.clamp(fileConfiguration.getDouble("consensus.maximum-confidence", 0.984), 0.5, 0.99);
        this.joinGraceMs = BridgeSettings.positive(fileConfiguration.getLong("safety.join-grace-ms", 2500L), 2500L);
        this.teleportGraceMs = BridgeSettings.positive(fileConfiguration.getLong("safety.teleport-grace-ms", 1500L), 1500L);
        this.velocityGraceMs = BridgeSettings.positive(fileConfiguration.getLong("safety.velocity-grace-ms", 1500L), 1500L);
        this.minimumTps = BridgeSettings.clamp(fileConfiguration.getDouble("safety.minimum-tps", 18.0), 1.0, 20.0);
        this.maximumPing = Math.max(50, fileConfiguration.getInt("safety.maximum-ping-ms", 250));
        this.debug = fileConfiguration.getBoolean("debug", false);
        this.enforce = "ENFORCE".equalsIgnoreCase(fileConfiguration.getString("mode", "ENFORCE"));
        this.ignoredWorlds = new HashSet<String>();
        List<String> list = fileConfiguration.getStringList("safety.ignored-worlds");
        for (String string : list) {
            this.ignoredWorlds.add(string.toLowerCase(Locale.ROOT));
        }
        for (BridgeTypes.Source source : BridgeTypes.Source.values()) {
            String string = "sources." + source.name().toLowerCase(Locale.ROOT);
            this.enabled.put(source, fileConfiguration.getBoolean(string + ".enabled", true));
            this.weight.put(source, BridgeSettings.clamp(fileConfiguration.getDouble(string + ".weight", BridgeSettings.defaultWeight(source)), 0.0, 2.0));
        }
        this.grimIgnoreExperimental = fileConfiguration.getBoolean("sources.grim.ignore-experimental", true);
        this.watchcatThreshold = BridgeSettings.clamp(fileConfiguration.getDouble("sources.watchcat.threshold", 0.72), 0.5, 1.0);
        this.watchcatResetThreshold = BridgeSettings.clamp(fileConfiguration.getDouble("sources.watchcat.reset-threshold", 0.48), 0.0, this.watchcatThreshold);
        this.watchcatMinimumRise = BridgeSettings.clamp(fileConfiguration.getDouble("sources.watchcat.minimum-rise", 0.04), 0.0, 1.0);
        this.watchcatRepeatMs = BridgeSettings.positive(fileConfiguration.getLong("sources.watchcat.repeat-ms", 1500L), 1500L);
        this.walrusThreshold = BridgeSettings.clamp(fileConfiguration.getDouble("sources.walrus.threshold", 0.83), 0.5, 1.0);
        this.walrusMinimumDetections = Math.max(1, fileConfiguration.getInt("sources.walrus.minimum-high-detections", 2));
        this.walrusRepeatMs = BridgeSettings.positive(fileConfiguration.getLong("sources.walrus.repeat-ms", 1500L), 1500L);
        this.punishmentsEnabled = fileConfiguration.getBoolean("punishments.enabled", true);
        this.consensusBanVerdicts = Math.max(1, fileConfiguration.getInt("punishments.consensus.required-verdicts", 1));
        this.consensusBanWindowMs = BridgeSettings.positive(fileConfiguration.getLong("punishments.consensus.window-ms", 30000L), 30000L);
        this.punishmentCooldownMs = BridgeSettings.positive(fileConfiguration.getLong("punishments.action-cooldown-ms", 2000L), 2000L);
        this.secondBanDays = Math.max(1, fileConfiguration.getInt("punishments.stages.second.duration-days", 15));
        this.thirdBanDays = Math.max(this.secondBanDays + 1, fileConfiguration.getInt("punishments.stages.third.duration-days", 40));
        this.ipBanEnabled = fileConfiguration.getBoolean("punishments.ip-ban.enabled", true);
        this.allowPrivateIpBan = fileConfiguration.getBoolean("punishments.ip-ban.allow-private-addresses", false);
        this.externalCommandsEnabled = fileConfiguration.getBoolean("punishments.external-commands.enabled", false);
        this.secondCommand = fileConfiguration.getString("punishments.external-commands.second", "tempban %player% 15d %reason% - %hacks%");
        this.thirdCommand = fileConfiguration.getString("punishments.external-commands.third", "tempban %player% 40d %reason% - %hacks%");
        this.fourthCommand = fileConfiguration.getString("punishments.external-commands.fourth", "ban-ip %ip% %reason% - %hacks%");
        this.wallHitEnabled = fileConfiguration.getBoolean("wall-hit.enabled", true);
        this.wallHitBanViolations = Math.max(2, fileConfiguration.getInt("wall-hit.ban-after-violations", 2));
        this.wallHitBanWindowMs = BridgeSettings.positive(fileConfiguration.getLong("wall-hit.window-ms", 8000L), 8000L);
        this.wallHitCountCooldownMs = BridgeSettings.positive(fileConfiguration.getLong("wall-hit.count-cooldown-ms", 250L), 250L);
        this.wallHitRayTolerance = BridgeSettings.clamp(fileConfiguration.getDouble("wall-hit.ray-tolerance", 0.12), 0.02, 0.4);
        this.wallHitMaximumDistance = BridgeSettings.clamp(fileConfiguration.getDouble("wall-hit.maximum-distance", 6.5), 3.0, 16.0);
        this.wallHitSentinelScore = BridgeSettings.clamp(fileConfiguration.getDouble("wall-hit.sentinel-score", 6.0), 0.1, 20.0);
        this.wallHitSentinelConfidence = BridgeSettings.clamp(fileConfiguration.getDouble("wall-hit.sentinel-confidence", 0.994), 0.8, 0.994);
        this.mobCombatEnabled = fileConfiguration.getBoolean("mob-npc-combat.enabled", true);
        this.mobCombatMaximumAngle = BridgeSettings.clamp(fileConfiguration.getDouble("mob-npc-combat.maximum-attack-angle-degrees", 80.0), 30.0, 150.0);
        this.mobCombatRotationViolations = Math.max(2, fileConfiguration.getInt("mob-npc-combat.rotation-punish-after-violations", 3));
        this.mobCombatRotationWindowMs = BridgeSettings.positive(fileConfiguration.getLong("mob-npc-combat.rotation-window-ms", 2000L), 2000L);
        this.mobCombatCountCooldownMs = BridgeSettings.positive(fileConfiguration.getLong("mob-npc-combat.count-cooldown-ms", 100L), 100L);
        this.mobCombatMultiTargets = Math.max(3, fileConfiguration.getInt("mob-npc-combat.multi-target-count", 3));
        this.mobCombatMultiTargetWindowMs = BridgeSettings.positive(fileConfiguration.getLong("mob-npc-combat.multi-target-window-ms", 600L), 600L);
        this.mobCombatMultiTargetRepeatMs = BridgeSettings.positive(fileConfiguration.getLong("mob-npc-combat.multi-target-repeat-ms", 1500L), 1500L);
        this.freeCamOcclusion = fileConfiguration.getBoolean("freecam.occlusion-check.enabled", false);
        this.freeCamMinimumSeriesMs = Math.max(1000L, fileConfiguration.getLong("freecam.minimum-series-ms", 1500L));
        this.interactionRangeTolerance = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.attribute-tolerance", 0.75), 0.25, 3.0);
        this.freeCamEnabled = fileConfiguration.getBoolean("freecam.enabled", true);
        this.freeCamPunishViolations = Math.max(4, fileConfiguration.getInt("freecam.punish-after-violations", 4));
        this.freeCamPunishWindowMs = BridgeSettings.positive(fileConfiguration.getLong("freecam.window-ms", 8000L), 8000L);
        this.freeCamCountCooldownMs = BridgeSettings.positive(fileConfiguration.getLong("freecam.count-cooldown-ms", 250L), 250L);
        this.freeCamMaximumBlockDistance = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.maximum-block-distance", 6.25), 4.0, 12.0);
        this.freeCamMaximumEntityDistance = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.maximum-entity-distance", 7.0), 4.0, 12.0);
        this.freeCamRayTolerance = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.ray-tolerance", 0.14), 0.02, 0.5);
        this.freeCamSkipPlaceOcclusion = fileConfiguration.getBoolean("freecam.skip-place-occlusion-check", true);
        this.freeCamSentinelScore = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.sentinel-score", 6.0), 0.1, 20.0);
        this.freeCamSentinelConfidence = BridgeSettings.clamp(fileConfiguration.getDouble("freecam.sentinel-confidence", 0.994), 0.8, 0.994);
        this.unbanSyncEnabled = fileConfiguration.getBoolean("unban-sync.enabled", true);
        this.unbanPlayerCommands = BridgeSettings.normalizedCommands(fileConfiguration.getStringList("unban-sync.player-commands"), Set.of("unban", "pardon"));
        this.unbanIpCommands = BridgeSettings.normalizedCommands(fileConfiguration.getStringList("unban-sync.ip-commands"), Set.of("unban-ip", "unbanip", "pardon-ip"));
        this.punishmentMessageTitle = fileConfiguration.getString("punishments.messages.title", "&c&lSENTINEL SECURITY");
        this.punishmentMessageSeparator = fileConfiguration.getString("punishments.messages.separator", "&8&m--------------------------------");
        this.punishmentMessageAppeal = fileConfiguration.getString("punishments.messages.appeal", "&7\u0421\u0447\u0438\u0442\u0430\u0435\u0442\u0435 \u043d\u0430\u043a\u0430\u0437\u0430\u043d\u0438\u0435 \u043e\u0448\u0438\u0431\u043e\u0447\u043d\u044b\u043c? \u041e\u0431\u0440\u0430\u0442\u0438\u0442\u0435\u0441\u044c \u043a \u0430\u0434\u043c\u0438\u043d\u0438\u0441\u0442\u0440\u0430\u0446\u0438\u0438.");
    }

    static BridgeSettings load(FileConfiguration fileConfiguration) {
        return new BridgeSettings(fileConfiguration);
    }

    boolean enabled(BridgeTypes.Source source) {
        return this.enabled.getOrDefault((Object)source, false);
    }

    double weight(BridgeTypes.Source source) {
        return this.weight.getOrDefault((Object)source, 0.0);
    }

    private static double defaultWeight(BridgeTypes.Source source) {
        return switch (source) {
            default -> throw new IncompatibleClassChangeError();
            case SENTINEL -> 1.1;
            case GRIM -> 1.0;
            case LMV -> 0.8;
            case WATCHCAT -> 0.9;
            case WALRUS -> 0.9;
            case GUARD -> 1.0;
        };
    }

    private static Set<String> normalizedCommands(List<String> list, Set<String> set) {
        HashSet<String> hashSet = new HashSet<String>();
        List<String> list2 = list == null || list.isEmpty() ? List.copyOf(set) : list;
        for (String string : list2) {
            int n;
            if (string == null) continue;
            String string2 = string.strip().toLowerCase(Locale.ROOT);
            if (string2.startsWith("/")) {
                string2 = string2.substring(1);
            }
            if ((n = string2.indexOf(58)) >= 0) {
                string2 = string2.substring(n + 1);
            }
            if (string2.isEmpty()) continue;
            hashSet.add(string2);
        }
        return hashSet;
    }

    private static long positive(long l, long l2) {
        return l > 0L ? l : l2;
    }

    private static double clamp(double d, double d2, double d3) {
        return Double.isFinite(d) ? Math.max(d2, Math.min(d3, d)) : d3;
    }
}

