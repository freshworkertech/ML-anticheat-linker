package dev.sentinel.bridge;

import dev.sentinel.anticheat.SentinelPlugin;
import dev.sentinel.api.Evidence;
import dev.sentinel.api.EvidenceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import java.util.HashMap;
import java.util.Map;

/** Native evidence arrives once, synchronously; bridge evidence is never re-imported. */
final class SentinelAdapter implements Adapter, Listener {
    private final SentinelBridgePlugin bridge;
    private final BridgeEngine engine;
    private final BridgeSettings settings;
    private final Map<String, Long> seen = new HashMap<>();
    private boolean active;

    SentinelAdapter(SentinelBridgePlugin bridge, BridgeEngine engine, SentinelPlugin sentinel, BridgeSettings settings) {
        this.bridge = bridge; this.engine = engine; this.settings = settings;
    }
    public String name() { return "SentinelAC native"; }
    public boolean enable() {
        active = settings.enabled(BridgeTypes.Source.SENTINEL);
        if (active) bridge.getServer().getPluginManager().registerEvents(this, bridge);
        return active;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEvidence(EvidenceEvent event) {
        Evidence evidence = event.evidence();
        if (!active || !"Sentinel".equalsIgnoreCase(evidence.source())) return;
        if ("bridge".equals(evidence.details().get("origin"))) return;
        String details = String.valueOf(evidence.details().getOrDefault("legacyDetails", ""));
        if (details.startsWith("SentinelBridge")) return;
        String check = String.valueOf(evidence.details().getOrDefault("checkId", evidence.check()));
        if (BridgeTypes.map(check) == null) return;
        String key = evidence.player() + ":" + evidence.incidentId();
        long now = System.currentTimeMillis();
        if (seen.putIfAbsent(key, now) != null) return;
        engine.accept(evidence.player(), BridgeTypes.Source.SENTINEL, check, evidence.confidence(), details);
        // Preserve the native repeated hard-geometry policy when the bridge owns sanctions.
        if (("REACH".equals(check) || "WALL_HIT".equals(check)) && evidence.confidence() >= 0.995
                && Boolean.TRUE.equals(evidence.details().get("impossibleGeometry"))
                && Boolean.TRUE.equals(evidence.details().get("confirmedSeries"))) {
            org.bukkit.entity.Player player = bridge.getServer().getPlayer(evidence.player());
            if (player != null && engine.safeToEvaluate(player, BridgeTypes.Family.COMBAT)) {
                bridge.punishments().recordNativeGeometry(player, check);
            }
        }
    }
    public void tick() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(entry -> now - entry.getValue() > settings.incidentWindowMs);
    }
    public void disable() { active = false; HandlerList.unregisterAll(this); seen.clear(); }
    public String status() { return active ? "EvidenceEvent API connected (no polling / no feedback)" : "disabled in config"; }
}
