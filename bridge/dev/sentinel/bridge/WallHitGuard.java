package dev.sentinel.bridge;

import dev.sentinel.anticheat.SentinelPlugin;
import dev.sentinel.anticheat.model.CheckType;
import dev.sentinel.bridge.BridgeEngine;
import dev.sentinel.bridge.BridgeSettings;
import dev.sentinel.bridge.BridgeTypes;
import dev.sentinel.bridge.GeometrySupport;
import dev.sentinel.bridge.PunishmentManager;
import dev.sentinel.bridge.ReflectionSupport;
import dev.sentinel.bridge.SentinelBridgePlugin;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;

final class WallHitGuard
implements Listener {
    private final SentinelBridgePlugin plugin;
    private final SentinelPlugin sentinel;
    private final BridgeEngine engine;
    private final PunishmentManager punishments;
    private final Map<UUID, Long> lastCounted = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastMobRotationCounted = new HashMap<UUID, Long>();
    private final Map<UUID, CombatTrack> mobCombat = new HashMap<UUID, CombatTrack>();

    WallHitGuard(SentinelBridgePlugin sentinelBridgePlugin, SentinelPlugin sentinelPlugin, BridgeEngine bridgeEngine, PunishmentManager punishmentManager) {
        this.plugin = sentinelBridgePlugin;
        this.sentinel = sentinelPlugin;
        this.engine = bridgeEngine;
        this.punishments = punishmentManager;
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onDamage(EntityDamageByEntityEvent entityDamageByEntityEvent) {
        long l;
        String string;
        BoundingBox boundingBox;
        Player player;
        Entity entity;
        if (entityDamageByEntityEvent.isCancelled() || entityDamageByEntityEvent.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!bridgeSettings.wallHitEnabled || !((entity = entityDamageByEntityEvent.getDamager()) instanceof Player) || (player = (Player)entity).hasPermission("sentinelbridge.bypass") || bridgeSettings.ignoredWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        if (!engine.safeToEvaluate(player, BridgeTypes.Family.COMBAT)) return;
        entity = entityDamageByEntityEvent.getEntity();
        if (!entity.isValid()) return;
        if (entity instanceof Player targetPlayer && !engine.safeToEvaluate(targetPlayer, BridgeTypes.Family.COMBAT)) return;
        if (player.getWorld() != entity.getWorld()) {
            return;
        }
        Location location = player.getEyeLocation();
        boundingBox = entity.getBoundingBox();
        double expansion = Math.min(1.25, entity.getVelocity().length() * (Math.max(0, player.getPing()) / 50.0 + 2.0));
        double d = FreeCamGuard.minimumDistance(sentinel, player, boundingBox.clone().expand(expansion));
        boolean bl = d > FreeCamGuard.allowedDistance(player, org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE, bridgeSettings.wallHitMaximumDistance, bridgeSettings);
        boolean bl2 = bl;
        boolean bl3 = this.isMobOrNpc(entity);
        String string2 = string = entity.getName() == null ? entity.getType().name() : entity.getName();
        if (!bl2) {
            if (bl3 && bridgeSettings.mobCombatEnabled) {
                this.inspectMobCombat(entityDamageByEntityEvent, player, entity, string, location, boundingBox, bridgeSettings);
            }
            return;
        }
        if (bridgeSettings.enforce) {
            entityDamageByEntityEvent.setCancelled(true);
        }
        if (this.engine.hasGrace(player, BridgeTypes.Family.COMBAT)) {
            return;
        }
        long l2 = System.currentTimeMillis();
        if (l2 - (l = this.lastCounted.getOrDefault(player.getUniqueId(), 0L).longValue()) < bridgeSettings.wallHitCountCooldownMs) {
            return;
        }
        this.lastCounted.put(player.getUniqueId(), l2);
        String string3 = "SentinelBridge WallHitGuard target=" + string + " distance=" + String.format(Locale.ROOT, "%.3f", d) + " reason=" + (bl ? "distance" : "occluded");
        try {
            this.sentinel.violations().flag(player, this.sentinel.profile(player), CheckType.REACH, bridgeSettings.wallHitSentinelScore, bridgeSettings.wallHitSentinelConfidence, string3);
        }
        catch (LinkageError | RuntimeException throwable) {
            this.plugin.logAdapterError("WallHit Sentinel evidence", throwable);
        }
        this.punishments.recordWallHit(player, string, bl3);
        this.plugin.getLogger().warning("Blocked impossible hit: " + player.getName() + " -> " + string + " distance=" + String.format(Locale.ROOT, "%.3f", d));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent playerQuitEvent) {
        this.lastCounted.remove(playerQuitEvent.getPlayer().getUniqueId());
        this.lastMobRotationCounted.remove(playerQuitEvent.getPlayer().getUniqueId());
        this.mobCombat.remove(playerQuitEvent.getPlayer().getUniqueId());
    }

    private void inspectMobCombat(EntityDamageByEntityEvent entityDamageByEntityEvent, Player player, Entity entity, String string, Location location, BoundingBox boundingBox, BridgeSettings bridgeSettings) {
        long l;
        double d;
        if (this.engine.hasGrace(player, BridgeTypes.Family.COMBAT) || this.isSweepAttack(entityDamageByEntityEvent)) {
            return;
        }
        long l2 = System.currentTimeMillis();
        UUID uUID2 = player.getUniqueId();
        CombatTrack combatTrack = this.mobCombat.computeIfAbsent(uUID2, uUID -> new CombatTrack());
        combatTrack.attacks.addLast(new AttackSample(l2, entity.getUniqueId()));
        while (!combatTrack.attacks.isEmpty() && l2 - combatTrack.attacks.peekFirst().time > bridgeSettings.mobCombatMultiTargetWindowMs) {
            combatTrack.attacks.removeFirst();
        }
        HashSet<UUID> hashSet = new HashSet<UUID>();
        for (AttackSample attackSample : combatTrack.attacks) {
            hashSet.add(attackSample.target);
        }
        double angle = GeometrySupport.minimumLookAngleDegrees(location, boundingBox);
        if (angle <= bridgeSettings.mobCombatMaximumAngle || GeometrySupport.distanceToBox(location, boundingBox) < 0.75) {
            combatTrack.invalidRotations = 0;
            return;
        }
        if (l2 - combatTrack.lastInvalidRotation > bridgeSettings.mobCombatRotationWindowMs) combatTrack.invalidRotations = 0;
        combatTrack.lastInvalidRotation = l2;
        if (++combatTrack.invalidRotations < bridgeSettings.mobCombatRotationViolations) return;
        d = angle;
        if (hashSet.size() >= bridgeSettings.mobCombatMultiTargets && l2 - combatTrack.lastMultiTargetEvidence >= bridgeSettings.mobCombatMultiTargetRepeatMs) {
            combatTrack.lastMultiTargetEvidence = l2;
            engine.accept(uUID2, BridgeTypes.Source.GUARD, "MultiAuraNPC", 0.93, "targets=" + hashSet.size() + " invalid-angle=" + angle);
        }
        if (bridgeSettings.enforce) {
            entityDamageByEntityEvent.setCancelled(true);
        }
        if (l2 - (l = this.lastMobRotationCounted.getOrDefault(uUID2, 0L).longValue()) < bridgeSettings.mobCombatCountCooldownMs) {
            return;
        }
        this.lastMobRotationCounted.put(uUID2, l2);
        double d2 = Math.min(0.99, 0.9 + (d - bridgeSettings.mobCombatMaximumAngle) / 1000.0);
        long l3 = this.punishments.actions();
        this.engine.accept(uUID2, BridgeTypes.Source.GUARD, "MobAuraRotation", d2, "target=" + string + " angle=" + String.format(Locale.ROOT, "%.2f", d));
        // Rotation alone has no independent lag rewind: require external consensus for sanctions.
        this.plugin.getLogger().warning("Blocked invalid mob/NPC attack rotation: " + player.getName() + " -> " + string + " angle=" + String.format(Locale.ROOT, "%.2f", d));
    }

    private boolean isSweepAttack(EntityDamageByEntityEvent entityDamageByEntityEvent) {
        try {
            String string = String.valueOf(ReflectionSupport.call(entityDamageByEntityEvent, "getCause", new Object[0]));
            return string.toUpperCase(Locale.ROOT).contains("SWEEP");
        }
        catch (LinkageError | ReflectiveOperationException | RuntimeException throwable) {
            return false;
        }
    }

    private boolean isMobOrNpc(Entity entity) {
        if (!(entity instanceof Player)) {
            return true;
        }
        try {
            return ReflectionSupport.bool(ReflectionSupport.call(entity, "hasMetadata", "NPC"), false) || ReflectionSupport.bool(ReflectionSupport.call(entity, "hasMetadata", "CitizensNPC"), false) || entity.getClass().getName().toLowerCase(Locale.ROOT).contains("npc");
        }
        catch (LinkageError | ReflectiveOperationException | RuntimeException throwable) {
            return entity.getClass().getName().toLowerCase(Locale.ROOT).contains("npc");
        }
    }

    private static final class CombatTrack {
        private final Deque<AttackSample> attacks = new ArrayDeque<AttackSample>();
        private long lastMultiTargetEvidence;
        private long lastInvalidRotation;
        private int invalidRotations;

        private CombatTrack() {
        }
    }

    private record AttackSample(long time, UUID target) {
    }
}

