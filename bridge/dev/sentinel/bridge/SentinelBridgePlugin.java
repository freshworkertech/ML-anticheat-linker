package dev.sentinel.bridge;

import dev.sentinel.anticheat.SentinelPlugin;
import dev.sentinel.bridge.Adapter;
import dev.sentinel.bridge.BridgeEngine;
import dev.sentinel.bridge.BridgeSettings;
import dev.sentinel.bridge.FreeCamGuard;
import dev.sentinel.bridge.GrimAdapter;
import dev.sentinel.bridge.LmvAdapter;
import dev.sentinel.bridge.PunishmentManager;
import dev.sentinel.bridge.ReflectionSupport;
import dev.sentinel.bridge.SafetyListener;
import dev.sentinel.bridge.SentinelAdapter;
import dev.sentinel.bridge.UnbanSyncListener;
import dev.sentinel.bridge.WallHitGuard;
import dev.sentinel.bridge.WalrusAdapter;
import dev.sentinel.bridge.WatchCatAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SentinelBridgePlugin
extends JavaPlugin implements dev.sentinel.api.PunishmentController {
    private final List<Adapter> adapters = new ArrayList<Adapter>();
    private final Map<String, Long> adapterErrorLog = new HashMap<String, Long>();
    private BridgeSettings settings;
    private BridgeEngine engine;
    private SentinelPlugin sentinel;
    private PunishmentManager punishments;
    private BukkitTask pollTask;
    private int ticks;

    public void onEnable() {
        SentinelPlugin sentinelPlugin;
        block4: {
            block3: {
                this.saveDefaultConfig();
                this.migrateSettings();
                this.settings = BridgeSettings.load(this.getConfig());
                Plugin plugin = this.getServer().getPluginManager().getPlugin("SentinelAC");
                if (!(plugin instanceof SentinelPlugin)) break block3;
                sentinelPlugin = (SentinelPlugin)plugin;
                if (plugin.isEnabled()) break block4;
            }
            this.getLogger().severe("SentinelAC 2.0.1 is required. SentinelBridge will be disabled.");
            this.getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.sentinel = sentinelPlugin;
        this.punishments = new PunishmentManager(this);
        this.engine = new BridgeEngine(this, this.sentinel, this.settings);
        this.getServer().getPluginManager().registerEvents((Listener)this.punishments, (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new SafetyListener(this, this.engine), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new WallHitGuard(this, this.sentinel, this.engine, this.punishments), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new FreeCamGuard(this, this.sentinel, this.engine, this.punishments), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new UnbanSyncListener(this), (Plugin)this);
        this.getServer().getServicesManager().register(dev.sentinel.api.PunishmentController.class, this, this, org.bukkit.plugin.ServicePriority.Normal);
        this.connectAdapters();
        for (Player online : getServer().getOnlinePlayers()) {
            engine.markGrace(online, settings.joinGraceMs, "bridge-start");
        }
        this.pollTask = this.getServer().getScheduler().runTaskTimer((Plugin)this, () -> {
            for (Adapter adapter : this.adapters) {
                try { adapter.tick(); }
                catch (LinkageError | RuntimeException error) { logAdapterError(adapter.name() + " tick", error); }
            }
            if (++this.ticks % 20 == 0) {
                this.engine.cleanup();
                this.punishments.cleanup();
            }
        }, 20L, 10L);
        this.getLogger().info("SentinelBridge 1.6.0 enabled in " + (this.settings.enforce ? "ENFORCE" : "OBSERVE") + " mode: " + this.engine.statusLine());
        for (Adapter adapter : this.adapters) {
            this.getLogger().info(adapter.name() + " -> " + adapter.status());
        }
    }

    public void onDisable() {
        if (this.pollTask != null) {
            this.pollTask.cancel();
        }
        this.getServer().getServicesManager().unregisterAll(this);
        this.disconnectAdapters();
        this.adapterErrorLog.clear();
        if (this.punishments != null) {
            this.punishments.shutdown();
        }
    }

    BridgeSettings settings() {
        return this.settings;
    }

    PunishmentManager punishments() {
        return this.punishments;
    }

    void logAdapterError(String string, Throwable throwable) {
        long l;
        long l2 = System.currentTimeMillis();
        if (l2 - (l = this.adapterErrorLog.getOrDefault(string, 0L).longValue()) >= 30000L) {
            this.adapterErrorLog.put(string, l2);
            this.getLogger().log(Level.WARNING, string + " adapter error (further errors muted for 30s)", throwable);
        }
    }

    private void connectAdapters() {
        this.adapters.clear();
        this.adapters.add(new SentinelAdapter(this, this.engine, this.sentinel, this.settings));
        this.adapters.add(new GrimAdapter(this, this.engine, this.settings));
        this.adapters.add(new LmvAdapter(this, this.engine, this.settings));
        this.adapters.add(new WatchCatAdapter(this, this.engine, this.settings));
        this.adapters.add(new WalrusAdapter(this, this.engine, this.settings));
        for (Adapter adapter : this.adapters) {
            try { adapter.enable(); }
            catch (LinkageError | RuntimeException error) { logAdapterError(adapter.name() + " enable", error); }
        }
    }

    private void disconnectAdapters() {
        for (Adapter adapter : this.adapters) {
            try {
                adapter.disable();
            }
            catch (RuntimeException runtimeException) {
                this.logAdapterError(adapter.name() + " disable", runtimeException);
            }
        }
        this.adapters.clear();
    }

    private void reloadBridge() {
        this.disconnectAdapters();
        this.reloadConfig();
        this.migrateSettings();
        this.settings = BridgeSettings.load(this.getConfig());
        this.engine.reload(this.settings);
        this.punishments.clearAllEvidence();
        for (Player online : getServer().getOnlinePlayers()) engine.markGrace(online, settings.joinGraceMs, "reload");
        this.connectAdapters();
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        String string2;
        if (!commandSender.hasPermission("sentinelbridge.admin")) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "No permission.");
            return true;
        }
        switch (string2 = stringArray.length == 0 ? "status" : stringArray[0].toLowerCase(Locale.ROOT)) {
            case "status": {
                commandSender.sendMessage(String.valueOf(ChatColor.AQUA) + "SentinelBridge: " + String.valueOf(ChatColor.WHITE) + this.engine.statusLine());
                commandSender.sendMessage(String.valueOf(ChatColor.GRAY) + "Mode: " + (this.settings.enforce ? "ENFORCE" : "OBSERVE"));
                commandSender.sendMessage("SentinelAC=" + sentinel.mode() + ", automatic sanctions=SentinelBridge, Creative combat=" + sentinel.getConfig().getBoolean("checks.combat-in-creative", true));
                commandSender.sendMessage(String.valueOf(ChatColor.GRAY) + "Punishment actions: " + this.punishments.actions());
                for (Adapter object : this.adapters) {
                    commandSender.sendMessage(String.valueOf(ChatColor.GRAY) + "- " + object.name() + ": " + object.status());
                }
                for (String string3 : this.engine.sourceStatusLines()) {
                    commandSender.sendMessage(String.valueOf(ChatColor.DARK_GRAY) + "  " + string3);
                }
                break;
            }
            case "player": {
                if (stringArray.length < 2) {
                    commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "Usage: /" + string + " player <name>");
                    return true;
                }
                Player player = this.getServer().getPlayer(stringArray[1]);
                if (player == null) {
                    commandSender.sendMessage(String.valueOf(ChatColor.RED) + "Player is not online.");
                    break;
                }
                commandSender.sendMessage(String.valueOf(ChatColor.AQUA) + this.engine.playerStatus(player));
                commandSender.sendMessage(String.valueOf(ChatColor.GRAY) + "History: " + this.punishments.historyStatus(player.getUniqueId()));
                break;
            }
            case "history": 
            case "reset": 
            case "unban": {
                if (stringArray.length < 2) {
                    commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "Usage: /" + string + " " + string2 + " <name|uuid>");
                    return true;
                }
                UUID uUID = this.resolvePlayerId(stringArray[1]);
                if (uUID == null) {
                    commandSender.sendMessage(String.valueOf(ChatColor.RED) + "Player/UUID was not found.");
                    break;
                }
                if (string2.equals("reset") || string2.equals("unban")) {
                    this.punishments.reset(uUID);
                    this.engine.forget(uUID);
                    this.sentinel.pardon(uUID, "SentinelBridge reset");
                    commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "SentinelBridge sanction and history cleared for " + String.valueOf(uUID) + ".");
                    break;
                }
                commandSender.sendMessage(String.valueOf(ChatColor.AQUA) + uUID.toString() + ": " + this.punishments.historyStatus(uUID));
                break;
            }
            case "reload": {
                this.reloadBridge();
                commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "SentinelBridge configuration reloaded.");
                break;
            }
            default: {
                commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "Usage: /" + string + " <status|player <name>|history <name|uuid>|unban <name|uuid>|reset <name|uuid>|reload>");
            }
        }
        return true;
    }


    @Override
    public boolean ownsAutomaticActions() { return isEnabled(); }

    SentinelPlugin sentinel() { return sentinel; }
    BridgeEngine engine() { return engine; }

    private void migrateSettings() {
        if (getConfig().getInt("config-version", 0) >= 160) return;
        java.nio.file.Path current = getDataFolder().toPath().resolve("config.yml");
        try {
            java.nio.file.Path backup = getDataFolder().toPath().resolve("config-before-1.6.0-" + System.currentTimeMillis() + ".yml");
            if (java.nio.file.Files.exists(current)) java.nio.file.Files.copy(current, backup);
        } catch (java.io.IOException e) {
            getLogger().warning("Could not back up configuration: " + e.getMessage());
            return;
        }
        if (!getConfig().isSet("freecam.occlusion-check.enabled")) getConfig().set("freecam.occlusion-check.enabled", false);
        if (!getConfig().isSet("freecam.minimum-series-ms")) getConfig().set("freecam.minimum-series-ms", 1500);
        if (!getConfig().isSet("freecam.attribute-tolerance")) getConfig().set("freecam.attribute-tolerance", 0.75);
        getConfig().set("freecam.punish-after-violations", Math.max(4, getConfig().getInt("freecam.punish-after-violations", 4)));
        getConfig().set("config-version", 160);
        saveConfig();
        getLogger().info("Freecam settings migrated to 1.6.0; old configuration was backed up.");
    }

    UUID resolvePlayerId(String string) {
        try {
            return UUID.fromString(string);
        }
        catch (IllegalArgumentException illegalArgumentException) {
            Player player = this.getServer().getPlayer(string);
            if (player != null) {
                return player.getUniqueId();
            }
            try {
                UUID uUID;
                Object object = ReflectionSupport.call(this.getServer(), "getOfflinePlayer", string);
                Object object2 = ReflectionSupport.call(object, "getUniqueId", new Object[0]);
                return object2 instanceof UUID ? (uUID = (UUID)object2) : null;
            }
            catch (LinkageError | ReflectiveOperationException | RuntimeException throwable) {
                return null;
            }
        }
    }
}

