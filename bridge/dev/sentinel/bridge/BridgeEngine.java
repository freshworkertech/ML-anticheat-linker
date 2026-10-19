package dev.sentinel.bridge;

import dev.sentinel.anticheat.SentinelPlugin;
import dev.sentinel.anticheat.model.CheckType;
import dev.sentinel.bridge.BridgeSettings;
import dev.sentinel.bridge.BridgeTypes;
import dev.sentinel.bridge.SentinelBridgePlugin;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class BridgeEngine {
    private final SentinelBridgePlugin plugin;
    private final SentinelPlugin sentinel;
    private BridgeSettings settings;
    private final Map<UUID, PlayerState> states = new HashMap<UUID, PlayerState>();
    private final Map<UUID, EnumMap<BridgeTypes.Family, Grace>> grace = new HashMap<UUID, EnumMap<BridgeTypes.Family, Grace>>();
    private final EnumMap<BridgeTypes.Source, AtomicLong> accepted = new EnumMap(BridgeTypes.Source.class);
    private final EnumMap<BridgeTypes.Source, AtomicLong> duplicates = new EnumMap(BridgeTypes.Source.class);
    private final AtomicLong verdicts = new AtomicLong();
    private final AtomicLong safetyDrops = new AtomicLong();

    BridgeEngine(SentinelBridgePlugin sentinelBridgePlugin, SentinelPlugin sentinelPlugin, BridgeSettings bridgeSettings) {
        this.plugin = sentinelBridgePlugin;
        this.sentinel = sentinelPlugin;
        this.settings = bridgeSettings;
        for (BridgeTypes.Source source : BridgeTypes.Source.values()) {
            this.accepted.put(source, new AtomicLong());
            this.duplicates.put(source, new AtomicLong());
        }
    }

    void reload(BridgeSettings bridgeSettings) {
        this.settings = bridgeSettings;
        this.states.clear();
        this.grace.clear();
    }

    void accept(UUID uUID2, BridgeTypes.Source source, String string, double d, String string2) {
        if (!this.plugin.getServer().isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> this.accept(uUID2, source, string, d, string2));
            return;
        }
        BridgeTypes.Mapping mapping = BridgeTypes.map(string);
        if (mapping == null || !this.settings.enabled(source) || !Double.isFinite(d) || d <= 0.0) {
            return;
        }
        Player player = this.plugin.getServer().getPlayer(uUID2);
        if (!this.safeToEvaluate(player, mapping.family())) {
            this.safetyDrops.incrementAndGet();
            return;
        }
        long l = System.currentTimeMillis();
        PlayerState playerState = this.states.computeIfAbsent(uUID2, uUID -> new PlayerState());
        this.prune(playerState, l);
        String string3 = source.name() + ":" + mapping.family().name() + ":" + mapping.type().name();
        Long l2 = playerState.lastObservation.get(string3);
        if (l2 != null && l - l2 < this.settings.duplicateWindowMs) {
            this.duplicates.get((Object)source).incrementAndGet();
            return;
        }
        playerState.lastObservation.put(string3, l);
        double d2 = BridgeEngine.clamp(d, 0.5, 0.99);
        BridgeTypes.Observation observation = new BridgeTypes.Observation(l, source, mapping.family(), mapping.type(), d2, BridgeEngine.sanitize(string, 42), BridgeEngine.sanitize(string2, 140));
        playerState.observations.add(observation);
        this.accepted.get((Object)source).incrementAndGet();
        if (this.settings.debug) {
            this.plugin.getLogger().info("evidence player=" + player.getName() + " source=" + source.display() + " check=" + observation.check() + " confidence=" + BridgeEngine.format(d2));
        }
        this.tryVerdict(player, playerState, mapping.family(), l);
    }

    private void tryVerdict(Player player, PlayerState playerState, BridgeTypes.Family family, long l) {
        double d;
        CheckType verdictType;
        long l2 = playerState.lastVerdict.getOrDefault((Object)family, 0L);
        if (l - l2 < this.settings.verdictCooldownMs) {
            return;
        }
        EnumMap<BridgeTypes.Source, BridgeTypes.Observation> enumMap = new EnumMap<BridgeTypes.Source, BridgeTypes.Observation>(BridgeTypes.Source.class);
        for (BridgeTypes.Observation observation3 : playerState.observations) {
            BridgeTypes.Observation observation4;
            if (observation3.family() != family || (observation4 = (BridgeTypes.Observation)enumMap.get((Object)observation3.source())) != null && !(this.strength(observation3) > this.strength(observation4))) continue;
            enumMap.put(observation3.source(), observation3);
        }
        // Native Sentinel and its geometry guard are one implementation, not two independent voters.
        if (enumMap.containsKey(BridgeTypes.Source.SENTINEL) && enumMap.containsKey(BridgeTypes.Source.GUARD)) {
            BridgeTypes.Source weaker = strength(enumMap.get(BridgeTypes.Source.SENTINEL)) >= strength(enumMap.get(BridgeTypes.Source.GUARD))
                ? BridgeTypes.Source.GUARD : BridgeTypes.Source.SENTINEL;
            enumMap.remove(weaker);
        }
        if (enumMap.size() < this.settings.minimumSources) {
            return;
        }
        double d2 = 0.0;
        double d3 = 0.0;
        double d4 = 0.0;
        for (BridgeTypes.Observation observation22 : enumMap.values()) {
            d = this.settings.weight(observation22.source());
            d2 += d * observation22.confidence();
            d3 += d * observation22.confidence();
            d4 += d;
        }
        if (d2 < this.settings.minimumWeight || d4 <= 0.0) {
            return;
        }
        BridgeTypes.Observation observation5 = enumMap.values().stream().max(Comparator.comparingDouble(this::strength)).orElseThrow();
        verdictType = observation5.type();
        d = Math.min(this.settings.maximumConfidence, d3 / d4);
        double d5 = this.settings.baseScore + (double)Math.max(0, enumMap.size() - this.settings.minimumSources) * 0.75 + Math.max(0.0, d2 - this.settings.minimumWeight) * 1.5;
        d5 = Math.min(this.settings.maximumScore, d5);
        ArrayList<String> arrayList = new ArrayList<String>();
        enumMap.values().stream().sorted(Comparator.comparing(observation -> observation.source().name())).forEach(observation -> arrayList.add(observation.source().display()));
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        playerState.observations.stream().filter(observation -> observation.family() == family).sorted(Comparator.comparingLong(BridgeTypes.Observation::time)).forEach(observation -> linkedHashSet.add(observation.source().display() + ":" + observation.check()));
        ArrayList<String> arrayList2 = new ArrayList<String>(linkedHashSet);
        String string = "SentinelBridge consensus sources=" + String.join((CharSequence)"+", arrayList) + " checks=" + String.join((CharSequence)",", arrayList2) + " strength=" + BridgeEngine.format(d2);
        try {
            this.sentinel.violations().flag(player, this.sentinel.profile(player), verdictType, d5, d, string);
            this.verdicts.incrementAndGet();
            playerState.lastVerdict.put(family, l);
            playerState.lastConsensus = string;
            playerState.lastConsensusAt = l;
            playerState.observations.removeIf(observation -> observation.family() == family);
            this.plugin.punishments().recordConsensus(player, verdictType, arrayList, arrayList2);
            this.plugin.getLogger().info("Consensus -> SentinelAC: " + player.getName() + " " + String.valueOf(verdictType) + " sources=" + String.join((CharSequence)"+", arrayList) + " score=" + BridgeEngine.format(d5) + " confidence=" + BridgeEngine.format(d));
        }
        catch (LinkageError | RuntimeException throwable) {
            this.plugin.getLogger().log(Level.WARNING, "SentinelAC rejected a bridge verdict for " + player.getName(), throwable);
        }
    }

    void markGrace(Player player, long l, String string, BridgeTypes.Family ... familyArray) {
        BridgeTypes.Family[] familyArray2;
        if (player == null) {
            return;
        }
        if (familyArray == null || familyArray.length == 0) {
            plugin.punishments().clearEvidence(player.getUniqueId());
        }
        long l2 = System.currentTimeMillis() + Math.max(0L, l);
        EnumMap enumMap = this.grace.computeIfAbsent(player.getUniqueId(), uUID -> new EnumMap(BridgeTypes.Family.class));
        for (BridgeTypes.Family family : familyArray2 = familyArray == null || familyArray.length == 0 ? BridgeTypes.Family.values() : familyArray) {
            Grace grace = (Grace)enumMap.get((Object)family);
            if (grace != null && l2 <= grace.until) continue;
            enumMap.put(family, new Grace(l2, string));
        }
        PlayerState playerState = this.states.get(player.getUniqueId());
        if (playerState != null) {
            Set<BridgeTypes.Family> set = Set.of(familyArray2);
            playerState.observations.removeIf(observation -> set.contains((Object)observation.family()));
        }
    }

    void forget(UUID uUID) {
        this.states.remove(uUID);
        this.grace.remove(uUID);
    }

    boolean hasGrace(Player player, BridgeTypes.Family family) {
        EnumMap<BridgeTypes.Family, Grace> enumMap = this.grace.get(player.getUniqueId());
        Grace grace = enumMap == null ? null : enumMap.get((Object)family);
        return grace != null && grace.until > System.currentTimeMillis();
    }

    void cleanup() {
        long l = System.currentTimeMillis();
        this.grace.values().forEach(enumMap -> enumMap.entrySet().removeIf(entry -> ((Grace)entry.getValue()).until < l));
        this.grace.entrySet().removeIf(entry -> ((EnumMap)entry.getValue()).isEmpty());
        Iterator<Map.Entry<UUID, PlayerState>> iterator = this.states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerState> entry2 = iterator.next();
            this.prune(entry2.getValue(), l);
            Player player = this.plugin.getServer().getPlayer(entry2.getKey());
            if (player != null && player.isOnline() || !entry2.getValue().observations.isEmpty()) continue;
            iterator.remove();
        }
    }

    String statusLine() {
        return "verdicts=" + this.verdicts.get() + ", accepted=" + this.acceptedTotal() + ", deduplicated=" + this.duplicatesTotal() + ", safety-drops=" + this.safetyDrops.get();
    }

    List<String> sourceStatusLines() {
        ArrayList<String> arrayList = new ArrayList<String>();
        for (BridgeTypes.Source source : BridgeTypes.Source.values()) {
            arrayList.add(source.display() + ": accepted=" + this.accepted.get((Object)source).get() + ", duplicate=" + this.duplicates.get((Object)source).get() + ", weight=" + BridgeEngine.format(this.settings.weight(source)));
        }
        return arrayList;
    }

    String playerStatus(Player player) {
        PlayerState playerState = this.states.get(player.getUniqueId());
        EnumMap<BridgeTypes.Family, Grace> enumMap = this.grace.get(player.getUniqueId());
        long l = System.currentTimeMillis();
        String string3 = "none";
        if (enumMap != null) {
            string3 = enumMap.entrySet().stream().filter(entry -> ((Grace)entry.getValue()).until > l).map(entry -> ((BridgeTypes.Family)((Object)((Object)entry.getKey()))).name() + ":" + ((Grace)entry.getValue()).reason).reduce((string, string2) -> string + "," + string2).orElse("none");
        }
        String string4 = String.valueOf(player.hasPermission("sentinelbridge.bypass") || player.hasPermission("sentinelac.bypass") || sentinel.isTrusted(player.getUniqueId()));
        if (playerState == null) {
            return player.getName() + ": pending=0, grace=" + string3 + ", bypass=" + string4;
        }
        return player.getName() + ": pending=" + playerState.observations.size() + ", grace=" + string3 + ", bypass=" + string4 + ", last=" + (playerState.lastConsensus == null ? "none" : playerState.lastConsensus);
    }

    boolean safeToEvaluate(Player player, BridgeTypes.Family family) {
        Grace grace;
        if (player == null || !player.isOnline() || player.hasPermission("sentinelbridge.bypass")) {
            return false;
        }
        if (player.getWorld() != null && this.settings.ignoredWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        EnumMap<BridgeTypes.Family, Grace> enumMap = this.grace.get(player.getUniqueId());
        Grace grace2 = grace = enumMap == null ? null : enumMap.get((Object)family);
        if (grace != null && grace.until > System.currentTimeMillis()) {
            return false;
        }
        if (sentinel.exemptions().combatExempt(player, sentinel.profile(player))) {
            return false;
        }
        if (this.getPing(player) > this.settings.maximumPing) {
            return false;
        }
        if (this.getTps() < this.settings.minimumTps) {
            return false;
        }
        return family != BridgeTypes.Family.MOVEMENT || player.getGameMode() != org.bukkit.GameMode.CREATIVE && !player.isFlying() && !player.getAllowFlight() && !player.isInsideVehicle() && !player.isGliding();
    }

    private int getPing(Player player) {
        try {
            Method method = player.getClass().getMethod("getPing", new Class[0]);
            return ((Number)method.invoke(player, new Object[0])).intValue();
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            return 0;
        }
    }

    private double getTps() {
        try {
            Method method = this.plugin.getServer().getClass().getMethod("getTPS", new Class[0]);
            double[] dArray = (double[])method.invoke(this.plugin.getServer(), new Object[0]);
            return dArray.length == 0 ? 20.0 : dArray[0];
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            return 20.0;
        }
    }

    private void prune(PlayerState playerState, long l) {
        playerState.observations.removeIf(observation -> l - observation.time() > this.settings.incidentWindowMs);
        playerState.lastObservation.entrySet().removeIf(entry -> l - (Long)entry.getValue() > this.settings.incidentWindowMs * 2L);
    }

    private double strength(BridgeTypes.Observation observation) {
        return this.settings.weight(observation.source()) * observation.confidence();
    }

    private long acceptedTotal() {
        return this.accepted.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private long duplicatesTotal() {
        return this.duplicates.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private static String sanitize(String string, int n) {
        if (string == null) {
            return "";
        }
        String string2 = string.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return string2.length() <= n ? string2 : string2.substring(0, n);
    }

    private static String format(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static double clamp(double d, double d2, double d3) {
        if (!Double.isFinite(d)) {
            return d2;
        }
        return Math.max(d2, Math.min(d3, d));
    }

    private static final class PlayerState {
        private final List<BridgeTypes.Observation> observations = new ArrayList<BridgeTypes.Observation>();
        private final Map<String, Long> lastObservation = new HashMap<String, Long>();
        private final EnumMap<BridgeTypes.Family, Long> lastVerdict = new EnumMap(BridgeTypes.Family.class);
        private String lastConsensus;
        private long lastConsensusAt;

        private PlayerState() {
        }
    }

    private record Grace(long until, String reason) {
    }
}

