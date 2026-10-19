package dev.sentinel.bridge;

import dev.sentinel.anticheat.SentinelPlugin;
import dev.sentinel.anticheat.model.PositionSnapshot;
import dev.sentinel.api.ContextSnapshot;
import dev.sentinel.api.Evidence;
import dev.sentinel.api.EvidenceCategory;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Detects server-visible remote interactions. A camera moving only on the client is not observable. */
final class FreeCamGuard implements Listener {
    private final SentinelBridgePlugin plugin;
    private final SentinelPlugin sentinel;
    private final BridgeEngine engine;
    private final PunishmentManager punishments;
    private final Map<UUID, Long> lastCounted = new HashMap<>();

    FreeCamGuard(SentinelBridgePlugin plugin, SentinelPlugin sentinel, BridgeEngine engine, PunishmentManager punishments) {
        this.plugin = plugin; this.sentinel = sentinel; this.engine = engine; this.punishments = punishments;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (block == null || (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)) return;
        // Bukkit has independent block/item cancellation states and may pre-cancel no-op actions.
        if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) return;
        Player player = event.getPlayer();
        BridgeSettings settings = plugin.settings();
        if (!eligible(player, settings) || !player.getWorld().equals(block.getWorld()) || block.getType().isAir()) return;
        // Collision boxes are not selection boxes (vines, plants and signs may be empty).
        BoundingBox box = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
        double distance = minimumDistance(sentinel, player, box);
        double allowed = allowedDistance(player, Attribute.BLOCK_INTERACTION_RANGE, settings.freeCamMaximumBlockDistance, settings);
        if (distance <= allowed) {
            if (settings.freeCamOcclusion && !isBlockPlacement(action, event.getItem())
                && !GeometrySupport.hasVisibleBlockSample(player.getWorld(), player.getEyeLocation(), block, box, settings.freeCamRayTolerance)
                && settings.debug) plugin.getLogger().info("RemoteInteract occlusion diagnostic only: " + player.getName());
            return;
        }
        if (settings.enforce) event.setCancelled(true);
        flag(player, "block:" + block.getType().name(), distance, allowed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        Entity target = event.getRightClicked();
        BridgeSettings settings = plugin.settings();
        if (!eligible(player, settings) || !target.isValid() || !player.getWorld().equals(target.getWorld())) return;
        if (target instanceof Player other && !engine.safeToEvaluate(other, BridgeTypes.Family.COMBAT)) return;
        double expansion = Math.min(1.25, target.getVelocity().length() * (Math.max(0, player.getPing()) / 50.0 + 2.0));
        BoundingBox box = target.getBoundingBox().clone().expand(expansion);
        double distance = minimumDistance(sentinel, player, box);
        double allowed = allowedDistance(player, Attribute.ENTITY_INTERACTION_RANGE, settings.freeCamMaximumEntityDistance, settings);
        if (distance <= allowed) return;
        if (settings.enforce) event.setCancelled(true);
        flag(player, "entity:" + target.getType().name(), distance, allowed);
    }

    private boolean eligible(Player player, BridgeSettings settings) {
        return settings.freeCamEnabled && engine.safeToEvaluate(player, BridgeTypes.Family.WORLD)
            && !engine.hasGrace(player, BridgeTypes.Family.WORLD);
    }

    static double allowedDistance(Player player, Attribute attribute, double configuredFloor, BridgeSettings settings) {
        AttributeInstance value = player.getAttribute(attribute);
        double fallback = 5.0;
        double range = value == null ? fallback : value.getValue();
        if (!Double.isFinite(range) || range < 0.0) return Double.POSITIVE_INFINITY;
        return Math.max(configuredFloor, range + settings.interactionRangeTolerance);
    }

    static double minimumDistance(SentinelPlugin sentinel, Player player, BoundingBox box) {
        double minimum = GeometrySupport.distanceToBox(player.getEyeLocation(), box);
        long now = System.currentTimeMillis();
        long window = Math.min(400L, Math.max(100L, player.getPing() + 100L));
        for (PositionSnapshot sample : sentinel.profile(player).history()) {
            long age = now - sample.timestampMillis();
            if (age < 0 || age > window || !sample.worldId().equals(player.getWorld().getUID())) continue;
            minimum = Math.min(minimum, GeometrySupport.distanceToBox(new Location(player.getWorld(), sample.eyeX(), sample.eyeY(), sample.eyeZ()), box));
        }
        return minimum;
    }

    private void flag(Player player, String target, double distance, double allowed) {
        long now = System.currentTimeMillis();
        BridgeSettings settings = plugin.settings();
        if (now - lastCounted.getOrDefault(player.getUniqueId(), 0L) < settings.freeCamCountCooldownMs) return;
        lastCounted.put(player.getUniqueId(), now);
        Location location = player.getLocation();
        String details = String.format(Locale.ROOT, "SentinelBridge RemoteInteract target=%s distance=%.3f allowed=%.3f", target, distance, allowed);
        ContextSnapshot context = new ContextSnapshot(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), player.getPing(), sentinel.exemptionManager().jitter(player.getUniqueId()), sentinel.ticks().approximateTps(), org.bukkit.Bukkit.getAverageTickTime(), player.getWorld().getName(), sentinel.api().activeExemptions(player), Map.of());
        sentinel.api().submitEvidence(new Evidence(player.getUniqueId(), "SentinelBridge", "RemoteInteract", EvidenceCategory.WORLD,
            settings.freeCamSentinelConfidence, settings.freeCamSentinelScore * 10.0, sentinel.ticks().currentTick(),
            player.getUniqueId() + ":remote:" + sentinel.ticks().currentTick(), context,
            Map.of("origin", "bridge", "legacyDetails", details, "distance", distance, "allowed", allowed)));
        // This path never masquerades as native Reach or KillAura evidence.
        punishments.recordFreeCam(player, "RemoteInteract (repeated impossible distance)");
        if (settings.debug) plugin.getLogger().info((settings.enforce ? "Blocked: " : "Observed: ") + details + " player=" + player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { lastCounted.remove(event.getPlayer().getUniqueId()); }

    static boolean isBlockPlacement(Action action, ItemStack item) {
        return action == Action.RIGHT_CLICK_BLOCK && item != null && item.getType().isBlock();
    }
}
