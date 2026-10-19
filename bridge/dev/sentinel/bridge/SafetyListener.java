package dev.sentinel.bridge;

import dev.sentinel.bridge.BridgeEngine;
import dev.sentinel.bridge.BridgeTypes;
import dev.sentinel.bridge.SentinelBridgePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

final class SafetyListener
implements Listener {
    private final SentinelBridgePlugin plugin;
    private final BridgeEngine engine;

    SafetyListener(SentinelBridgePlugin sentinelBridgePlugin, BridgeEngine bridgeEngine) {
        this.plugin = sentinelBridgePlugin;
        this.engine = bridgeEngine;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent playerJoinEvent) {
        this.engine.markGrace(playerJoinEvent.getPlayer(), this.plugin.settings().joinGraceMs, "join", new BridgeTypes.Family[0]);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent playerTeleportEvent) {
        this.engine.markGrace(playerTeleportEvent.getPlayer(), this.plugin.settings().teleportGraceMs, "teleport", new BridgeTypes.Family[0]);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent playerChangedWorldEvent) {
        this.engine.markGrace(playerChangedWorldEvent.getPlayer(), this.plugin.settings().teleportGraceMs, "world-change", new BridgeTypes.Family[0]);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onVelocity(PlayerVelocityEvent playerVelocityEvent) {
        this.engine.markGrace(playerVelocityEvent.getPlayer(), this.plugin.settings().velocityGraceMs, "velocity", BridgeTypes.Family.MOVEMENT);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        engine.markGrace(event.getPlayer(), plugin.settings().teleportGraceMs, "respawn");
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onGameMode(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        engine.markGrace(event.getPlayer(), plugin.settings().teleportGraceMs, "gamemode");
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent playerQuitEvent) {
        this.plugin.punishments().clearEvidence(playerQuitEvent.getPlayer().getUniqueId());
        this.engine.forget(playerQuitEvent.getPlayer().getUniqueId());
    }
}

