package dev.sentinel.bridge;

import dev.sentinel.anticheat.model.CheckType;
import dev.sentinel.bridge.BridgeSettings;
import dev.sentinel.bridge.ReflectionSupport;
import dev.sentinel.bridge.SentinelBridgePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

final class PunishmentManager
implements Listener {
    private static final long DAY_MS = Duration.ofDays(1L).toMillis();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withLocale(Locale.ROOT).withZone(ZoneId.systemDefault());
    private final SentinelBridgePlugin plugin;
    private final Map<UUID, Track> tracks = new ConcurrentHashMap<UUID, Track>();
    private final Map<UUID, Sanction> sanctions = new ConcurrentHashMap<UUID, Sanction>();
    private final Map<String, String> permanentlyBannedIps = new ConcurrentHashMap<String, String>();
    private final AtomicLong actions = new AtomicLong();
    private final Path historyFile;

    PunishmentManager(SentinelBridgePlugin sentinelBridgePlugin) {
        this.plugin = sentinelBridgePlugin;
        this.historyFile = sentinelBridgePlugin.getDataFolder().toPath().resolve("offenses.properties");
        this.load();
    }

    void recordConsensus(Player player, CheckType checkType, List<String> list, List<String> list2) {
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!this.canPunish(player, bridgeSettings)) {
            return;
        }
        long l = System.currentTimeMillis();
        Track track = this.tracks.computeIfAbsent(player.getUniqueId(), uUID -> new Track());
        PunishmentManager.addHacks(track.hacks, list2);
        if (track.hacks.isEmpty()) {
            track.hacks.add(PunishmentManager.prettyHack(checkType.name()));
        }
        PunishmentManager.append(track.consensus, l, bridgeSettings.consensusBanWindowMs);
        this.plugin.getLogger().warning("ENFORCE consensus progress for " + player.getName() + ": " + track.consensus.size() + "/" + bridgeSettings.consensusBanVerdicts + " hacks=" + String.join((CharSequence)" - ", track.hacks) + " sources=" + String.join((CharSequence)"+", list));
        if (track.consensus.size() >= bridgeSettings.consensusBanVerdicts) {
            this.enforceNextStage(player, track, String.join((CharSequence)"+", list), l);
        }
    }

    void recordNativeGeometry(Player player, String check) {
        BridgeSettings settings = plugin.settings();
        if (!canPunish(player, settings)) return;
        long now = System.currentTimeMillis();
        Track track = tracks.computeIfAbsent(player.getUniqueId(), id -> new Track());
        track.hacks.add("Native " + check);
        append(track.nativeGeometry, now, settings.wallHitBanWindowMs);
        if (track.nativeGeometry.size() >= Math.max(3, settings.wallHitBanViolations)
                && now - track.nativeGeometry.peekFirst() >= 1000L) {
            enforceNextStage(player, track, "SentinelAC confirmed geometry", now);
        }
    }

    void recordWallHit(Player player, String string, boolean bl) {
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!this.canPunish(player, bridgeSettings)) {
            return;
        }
        long l = System.currentTimeMillis();
        Track track = this.tracks.computeIfAbsent(player.getUniqueId(), uUID -> new Track());
        track.hacks.add(bl ? "Mob/NPC Reach / WallHit" : "WallHit (\u0443\u0434\u0430\u0440\u044b \u0447\u0435\u0440\u0435\u0437 \u0431\u043b\u043e\u043a\u0438)");
        PunishmentManager.append(track.wallHits, l, bridgeSettings.wallHitBanWindowMs);
        this.plugin.getLogger().warning("ENFORCE WallHit progress for " + player.getName() + ": " + track.wallHits.size() + "/" + bridgeSettings.wallHitBanViolations + " target=" + string);
        if (track.wallHits.size() >= bridgeSettings.wallHitBanViolations) {
            this.enforceNextStage(player, track, "WallHitGuard", l);
        }
    }

    void recordFreeCam(Player player, String string) {
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!this.canPunish(player, bridgeSettings)) {
            return;
        }
        long l = System.currentTimeMillis();
        Track track = this.tracks.computeIfAbsent(player.getUniqueId(), uUID -> new Track());
        track.hacks.add(string);
        PunishmentManager.append(track.remoteActions, l, bridgeSettings.freeCamPunishWindowMs);
        this.plugin.getLogger().warning("ENFORCE FreeCam progress for " + player.getName() + ": " + track.remoteActions.size() + "/" + bridgeSettings.freeCamPunishViolations);
        if (track.remoteActions.size() >= bridgeSettings.freeCamPunishViolations && l - track.remoteActions.peekFirst() >= bridgeSettings.freeCamMinimumSeriesMs) {
            this.enforceNextStage(player, track, "FreeCamGuard", l);
        }
    }

    void recordMobCombat(Player player, String string) {
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!this.canPunish(player, bridgeSettings)) {
            return;
        }
        long l = System.currentTimeMillis();
        Track track = this.tracks.computeIfAbsent(player.getUniqueId(), uUID -> new Track());
        track.hacks.add(string);
        PunishmentManager.append(track.mobCombat, l, bridgeSettings.mobCombatRotationWindowMs);
        this.plugin.getLogger().warning("ENFORCE Mob/NPC combat progress for " + player.getName() + ": " + track.mobCombat.size() + "/" + bridgeSettings.mobCombatRotationViolations);
        if (track.mobCombat.size() >= bridgeSettings.mobCombatRotationViolations) {
            this.enforceNextStage(player, track, "MobNpcCombatGuard", l);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent asyncPlayerPreLoginEvent) {
        String string;
        long l = System.currentTimeMillis();
        String string2 = PunishmentManager.address(asyncPlayerPreLoginEvent.getAddress());
        String string3 = string = string2.isEmpty() ? null : this.permanentlyBannedIps.get(string2);
        if (string != null) {
            asyncPlayerPreLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, this.permanentMessage(string, true));
            return;
        }
        Sanction sanction = this.sanctions.get(asyncPlayerPreLoginEvent.getUniqueId());
        if (sanction == null || sanction.stage < 2) {
            return;
        }
        if (sanction.stage >= 4 || sanction.bannedUntil == Long.MAX_VALUE) {
            asyncPlayerPreLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, this.permanentMessage(sanction.hacks, !sanction.ip.isEmpty()));
        } else if (sanction.bannedUntil > l) {
            asyncPlayerPreLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, this.stageMessage(sanction.stage, sanction.bannedUntil, sanction.hacks, !sanction.ip.isEmpty()));
        }
    }

    void cleanup() {
        BridgeSettings bridgeSettings = this.plugin.settings();
        long l = System.currentTimeMillis();
        this.tracks.values().forEach(track -> {
            PunishmentManager.prune(track.consensus, l, bridgeSettings.consensusBanWindowMs);
            PunishmentManager.prune(track.nativeGeometry, l, bridgeSettings.wallHitBanWindowMs);
            PunishmentManager.prune(track.wallHits, l, bridgeSettings.wallHitBanWindowMs);
            PunishmentManager.prune(track.remoteActions, l, bridgeSettings.freeCamPunishWindowMs);
            PunishmentManager.prune(track.mobCombat, l, bridgeSettings.mobCombatRotationWindowMs);
        });
        this.tracks.entrySet().removeIf(entry -> ((Track)entry.getValue()).nativeGeometry.isEmpty() && ((Track)entry.getValue()).consensus.isEmpty() && ((Track)entry.getValue()).wallHits.isEmpty() && ((Track)entry.getValue()).remoteActions.isEmpty() && ((Track)entry.getValue()).mobCombat.isEmpty() && l - ((Track)entry.getValue()).lastAction > bridgeSettings.punishmentCooldownMs);
    }

    long actions() {
        return this.actions.get();
    }

    String historyStatus(UUID uUID) {
        Sanction sanction = this.sanctions.get(uUID);
        if (sanction == null) {
            return "offenses=0";
        }
        String string = sanction.bannedUntil <= 0L ? "none" : (sanction.bannedUntil == Long.MAX_VALUE ? "permanent" : DATE_FORMAT.format(Instant.ofEpochMilli(sanction.bannedUntil)));
        return "offenses=" + sanction.stage + ", banned-until=" + string + ", hacks=" + sanction.hacks;
    }

    boolean reset(UUID uUID) {
        Sanction sanction = this.sanctions.remove(uUID);
        this.tracks.remove(uUID);
        if (sanction != null && !sanction.ip.isEmpty()) {
            this.permanentlyBannedIps.remove(sanction.ip);
        }
        this.save();
        return sanction != null;
    }

    boolean resetIp(String string) {
        boolean bl;
        if (string == null) {
            return false;
        }
        String string2 = string.strip();
        if (string2.startsWith("/")) {
            string2 = string2.substring(1);
        }
        boolean bl2 = bl = this.permanentlyBannedIps.remove(string2) != null;
        if (bl) {
            this.save();
        }
        return bl;
    }

    void clearEvidence(UUID id) {
        Track track = tracks.get(id);
        if (track == null) return;
        track.nativeGeometry.clear();
        track.consensus.clear(); track.wallHits.clear(); track.remoteActions.clear();
        track.mobCombat.clear(); track.hacks.clear();
    }

    void clearAllEvidence() { for (UUID id : tracks.keySet()) clearEvidence(id); }

    void shutdown() {
        this.tracks.clear();
        this.save();
    }

    private boolean canPunish(Player player, BridgeSettings bridgeSettings) {
        return bridgeSettings.enforce && bridgeSettings.punishmentsEnabled && plugin.engine().safeToEvaluate(player, BridgeTypes.Family.COMBAT);
    }

    private void enforceNextStage(Player player, Track track, String string, long l) {
        Object object;
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (l - track.lastAction < bridgeSettings.punishmentCooldownMs) {
            return;
        }
        Sanction sanction = this.sanctions.get(player.getUniqueId());
        int n = Math.min(4, sanction == null ? 1 : sanction.stage + 1);
        String string2 = String.join((CharSequence)"\u001f", track.hacks);
        long l2 = switch (n) {
            case 2 -> l + (long)bridgeSettings.secondBanDays * DAY_MS;
            case 3 -> l + (long)bridgeSettings.thirdBanDays * DAY_MS;
            case 4 -> Long.MAX_VALUE;
            default -> 0L;
        };
        String string3 = "";
        if (n >= 4 && bridgeSettings.ipBanEnabled) {
            object = PunishmentManager.clientAddress(player);
            if (object != null && PunishmentManager.validIpForBan((InetAddress)object, bridgeSettings.allowPrivateIpBan)) {
                string3 = PunishmentManager.address((InetAddress)object);
                this.permanentlyBannedIps.put(string3, string2);
            } else {
                this.plugin.getLogger().warning("Stage 4 for " + player.getName() + " is UUID-permanent only: real forwarded IP is unavailable or private");
            }
        }
        object = new Sanction(n, l2, player.getName(), string2, string3);
        this.sanctions.put(player.getUniqueId(), (Sanction)object);
        this.save();
        String string4 = PunishmentManager.stageReason(n);
        String string5 = PunishmentManager.oneLineHacks(string2);
        boolean bl = this.dispatchStageCommand(player, n, string3, string4, string5);
        String string6 = this.stageMessage(n, l2, string2, !string3.isEmpty());
        boolean bl2 = this.kickSafely(player, string6);
        track.lastAction = l;
        track.nativeGeometry.clear();
        track.consensus.clear();
        track.wallHits.clear();
        track.remoteActions.clear();
        track.mobCombat.clear();
        track.hacks.clear();
        this.actions.incrementAndGet();
        this.broadcastBan(player, n, l, l2);
        this.plugin.getLogger().warning("ENFORCE stage " + n + " for " + player.getName() + ": command=" + bl + " kick=" + bl2 + " sources=" + string + " hacks=" + string5);
    }

    private void broadcastBan(Player player, int stage, long issuedAt, long bannedUntil) {
        // The first stage is a kick, not a ban. Do not broadcast on login denial.
        if (stage < 2 || !this.plugin.getConfig().getBoolean("punishments.broadcast.enabled", true)) {
            return;
        }
        List<String> defaults = List.of(
            "&8-----------------------------------------------",
            "&fИгрока &c%player% &fтолько что отбросило в бан по причине использования читов!",
            "&7&oМы этого не хотели. Но sentinelAC уже нажал на кнопку.",
            "&fСрок бана: &c%duration%&f.",
            "&8-----------------------------------------------"
        );
        List<String> lines = this.plugin.getConfig().isList("punishments.broadcast.lines")
                ? this.plugin.getConfig().getStringList("punishments.broadcast.lines") : defaults;
        try {
            if (lines.isEmpty()) {
                return;
            }
            lines = new java.util.ArrayList<>(lines);
            // Existing 1.5.2 templates also display the duration without a config reset.
            if (lines.stream().noneMatch(line -> line.contains("%duration%"))) {
                lines.add(Math.max(0, lines.size() - 1), "&fСрок бана: &c%duration%&f.");
            }
            String duration = formatBanDuration(issuedAt, bannedUntil);
            String message = color(String.join("\n", lines)
                    .replace("%player%", player.getName())
                    .replace("%duration%", duration)) + "\u00a7r";
            // Send directly to everyone online, without a broadcast permission filter.
            for (Player recipient : this.plugin.getServer().getOnlinePlayers()) {
                recipient.sendMessage(message);
            }
        } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("Could not broadcast ban for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private static String formatBanDuration(long issuedAt, long bannedUntil) {
        if (bannedUntil == Long.MAX_VALUE) {
            return "навсегда";
        }
        long days = Duration.ofMillis(Math.max(0L, bannedUntil - issuedAt)).toDays();
        long lastTwo = days % 100;
        String unit = lastTwo >= 11 && lastTwo <= 14 ? "дней"
                : days % 10 == 1 ? "день"
                : days % 10 >= 2 && days % 10 <= 4 ? "дня" : "дней";
        return days + " " + unit;
    }

    private boolean dispatchStageCommand(Player player, int n, String string, String string2, String string3) {
        String string4;
        BridgeSettings bridgeSettings = this.plugin.settings();
        if (!bridgeSettings.externalCommandsEnabled || n == 1) {
            return false;
        }
        if (n >= 4 && string.isEmpty()) {
            return false;
        }
        string4 = switch (n) {
            case 2 -> bridgeSettings.secondCommand;
            case 3 -> bridgeSettings.thirdCommand;
            default -> bridgeSettings.fourthCommand;
        };
        if (string4 == null || string4.isBlank()) {
            return false;
        }
        String string6 = string4.replace("%player%", PunishmentManager.safeToken(player.getName())).replace("%ip%", PunishmentManager.safeIp(string)).replace("%reason%", PunishmentManager.safeReason(string2)).replace("%hacks%", PunishmentManager.safeReason(string3)).replace("%stage%", Integer.toString(n));
        return this.dispatchSafely(string6);
    }

    private boolean dispatchSafely(String string) {
        try {
            Server server = this.plugin.getServer();
            Object object = ReflectionSupport.call(server, "getConsoleSender", new Object[0]);
            Object object2 = ReflectionSupport.call(server, "dispatchCommand", object, string);
            boolean bl = ReflectionSupport.bool(object2, false);
            if (!bl) {
                this.plugin.getLogger().warning("External punishment command rejected: " + string);
            }
            return bl;
        }
        catch (LinkageError | ReflectiveOperationException | RuntimeException throwable) {
            this.plugin.getLogger().warning("External punishment command failed; built-in sanction remains active: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private boolean kickSafely(Player player, String string) {
        if (!player.isOnline()) {
            return false;
        }
        try {
            player.kickPlayer(string);
            return true;
        }
        catch (LinkageError | RuntimeException throwable) {
            this.plugin.getLogger().warning("Could not kick " + player.getName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private synchronized void load() {
        if (!Files.isRegularFile(this.historyFile, new LinkOption[0])) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(this.historyFile, new OpenOption[0]);){
            properties.load(inputStream);
            for (String string : properties.stringPropertyNames()) {
                if (string.startsWith("player.") && string.endsWith(".stage")) {
                    String string2 = string.substring(7, string.length() - 6);
                    UUID uUID = UUID.fromString(string2);
                    String string3 = "player." + string2 + ".";
                    int n = Integer.parseInt(properties.getProperty(string3 + "stage", "0"));
                    long l = Long.parseLong(properties.getProperty(string3 + "until", "0"));
                    String string4 = PunishmentManager.decode(properties.getProperty(string3 + "name", ""));
                    String string5 = PunishmentManager.decode(properties.getProperty(string3 + "hacks", ""));
                    String string6 = PunishmentManager.decode(properties.getProperty(string3 + "ip", ""));
                    this.sanctions.put(uUID, new Sanction(n, l, string4, string5, string6));
                    continue;
                }
                if (!string.startsWith("ip.")) continue;
                this.permanentlyBannedIps.put(PunishmentManager.decode(string.substring(3)), PunishmentManager.decode(properties.getProperty(string, "")));
            }
        }
        catch (IOException | IllegalArgumentException exception) {
            this.plugin.getLogger().warning("Could not load offenses.properties: " + exception.getMessage());
        }
    }

    private synchronized void save() {
        Properties properties = new Properties();
        for (Map.Entry<UUID, Sanction> object : this.sanctions.entrySet()) {
            String string = "player." + String.valueOf(object.getKey()) + ".";
            Sanction sanction = object.getValue();
            properties.setProperty(string + "stage", Integer.toString(sanction.stage));
            properties.setProperty(string + "until", Long.toString(sanction.bannedUntil));
            properties.setProperty(string + "name", PunishmentManager.encode(sanction.lastName));
            properties.setProperty(string + "hacks", PunishmentManager.encode(sanction.hacks));
            properties.setProperty(string + "ip", PunishmentManager.encode(sanction.ip));
        }
        for (Map.Entry<String, String> entry : this.permanentlyBannedIps.entrySet()) {
            properties.setProperty("ip." + PunishmentManager.encode((String)entry.getKey()), PunishmentManager.encode((String)entry.getValue()));
        }
        try {
            Files.createDirectories(this.historyFile.getParent(), new FileAttribute[0]);
            Path path = this.historyFile.resolveSibling(String.valueOf(this.historyFile.getFileName()) + ".tmp");
            try (OutputStream outputStream = Files.newOutputStream(path, new OpenOption[0]);){
                properties.store(outputStream, "SentinelBridge persistent offense history");
            }
            try {
                Files.move(path, this.historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException atomicMoveNotSupportedException) {
                Files.move(path, this.historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException iOException) {
            this.plugin.getLogger().severe("Could not save offenses.properties: " + iOException.getMessage());
        }
    }

    private static String stageReason(int n) {
        return switch (n) {
            case 1 -> "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0435 \u0447\u0438\u0442\u043e\u0432";
            case 2 -> "\u041d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u043e\u0435 \u043f\u043e\u0432\u0442\u043e\u0440\u043d\u043e\u0435 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0435 \u0447\u0438\u0442\u043e\u0432";
            case 3 -> "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0435 \u0447\u0438\u0442-\u043a\u043b\u0438\u0435\u043d\u0442\u043e\u0432 \u0438\u043b\u0438 \u0437\u0430\u043f\u0440\u0435\u0449\u0451\u043d\u043d\u043e\u0433\u043e \u041f\u041e";
            default -> "\u0421\u0438\u0441\u0442\u0435\u043c\u0430\u0442\u0438\u0447\u0435\u0441\u043a\u043e\u0435 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0435 \u0447\u0438\u0442-\u043a\u043b\u0438\u0435\u043d\u0442\u043e\u0432 \u0438 \u043e\u0431\u0445\u043e\u0434 \u043d\u0430\u043a\u0430\u0437\u0430\u043d\u0438\u0439";
        };
    }

    private String stageMessage(int n, long l, String string, boolean bl) {
        BridgeSettings bridgeSettings = this.plugin.settings();
        String string2 = PunishmentManager.color(bridgeSettings.punishmentMessageSeparator);
        StringBuilder stringBuilder = new StringBuilder(PunishmentManager.color(bridgeSettings.punishmentMessageTitle)).append('\n').append(string2).append("\n\u00a7f\u041f\u0440\u0438\u0447\u0438\u043d\u0430: \u00a7c").append(PunishmentManager.stageReason(n)).append("\n\u00a77\u041d\u0430\u0440\u0443\u0448\u0435\u043d\u0438\u0435: \u00a7f").append(n).append("\u00a77/\u00a7f4");
        if (n == 1) {
            stringBuilder.append("\n\u00a7f\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u0435: \u00a7e\u043e\u0442\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435 \u043e\u0442 \u0441\u0435\u0440\u0432\u0435\u0440\u0430");
        } else if (n == 2) {
            stringBuilder.append("\n\u00a7f\u0421\u0440\u043e\u043a \u0431\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u043a\u0438: \u00a7e").append(bridgeSettings.secondBanDays).append(" \u0434\u043d\u0435\u0439");
        } else if (n == 3) {
            stringBuilder.append("\n\u00a7f\u0421\u0440\u043e\u043a \u0431\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u043a\u0438: \u00a7e").append(bridgeSettings.thirdBanDays).append(" \u0434\u043d\u0435\u0439");
        } else if (n >= 4) {
            stringBuilder.append(bl ? "\n\u00a7f\u0411\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u043a\u0430: \u00a74\u00a7l\u043d\u0430\u0432\u0441\u0435\u0433\u0434\u0430 \u00a77(UUID + IP)" : "\n\u00a7f\u0411\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u043a\u0430: \u00a74\u00a7l\u043d\u0430\u0432\u0441\u0435\u0433\u0434\u0430 \u00a77(UUID)");
        }
        if (l > 0L && l != Long.MAX_VALUE) {
            stringBuilder.append("\n\u00a7f\u041e\u043a\u043e\u043d\u0447\u0430\u043d\u0438\u0435: \u00a7e").append(DATE_FORMAT.format(Instant.ofEpochMilli(l)));
        }
        stringBuilder.append("\n\n\u00a7f\u041e\u0431\u043d\u0430\u0440\u0443\u0436\u0435\u043d\u043d\u044b\u0435 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438:").append(PunishmentManager.coloredBulletHacks(string)).append('\n').append(string2);
        String string3 = PunishmentManager.color(bridgeSettings.punishmentMessageAppeal);
        if (!string3.isBlank()) {
            stringBuilder.append('\n').append(string3);
        }
        return stringBuilder.toString();
    }

    private String permanentMessage(String string, boolean bl) {
        return this.stageMessage(4, Long.MAX_VALUE, string, bl);
    }

    private static String bulletHacks(String string) {
        StringBuilder stringBuilder = new StringBuilder();
        for (String string2 : PunishmentManager.splitHacks(string)) {
            stringBuilder.append("\n- ").append(string2);
        }
        if (stringBuilder.length() == 0) {
            stringBuilder.append("\n- \u041d\u0435\u0438\u0437\u0432\u0435\u0441\u0442\u043d\u0430\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430");
        }
        return stringBuilder.toString();
    }

    private static String coloredBulletHacks(String string) {
        StringBuilder stringBuilder = new StringBuilder();
        for (String string2 : PunishmentManager.splitHacks(string)) {
            stringBuilder.append("\n\u00a78\u2022 \u00a7f").append(string2);
        }
        if (stringBuilder.length() == 0) {
            stringBuilder.append("\n\u00a78\u2022 \u00a7f\u041d\u0435\u0438\u0437\u0432\u0435\u0441\u0442\u043d\u0430\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430");
        }
        return stringBuilder.toString();
    }

    private static String color(String string) {
        if (string == null || string.isEmpty()) {
            return "";
        }
        char[] cArray = string.toCharArray();
        String string2 = "0123456789abcdefklmnorx";
        int n = 0;
        while (n + 1 < cArray.length) {
            if (cArray[n] == '&' && string2.indexOf(Character.toLowerCase(cArray[n + 1])) >= 0) {
                cArray[n] = 167;
                cArray[n + 1] = Character.toLowerCase(cArray[n + 1]);
            }
            ++n;
        }
        return new String(cArray);
    }

    private static String oneLineHacks(String string) {
        return String.join((CharSequence)" - ", PunishmentManager.splitHacks(string));
    }

    private static List<String> splitHacks(String string) {
        if (string == null || string.isBlank()) {
            return List.of();
        }
        return List.of(string.split("\u001f", -1));
    }

    private static void addHacks(Set<String> set, List<String> list) {
        if (list == null) {
            return;
        }
        for (String string : list) {
            String string2;
            String string3 = string;
            int n = string3.indexOf(58);
            if (n >= 0 && n + 1 < string3.length()) {
                string3 = string3.substring(n + 1);
            }
            if ((string2 = PunishmentManager.prettyHack(string3)).isBlank()) continue;
            set.add(string2);
        }
    }

    private static String prettyHack(String string) {
        if (string == null) {
            return "";
        }
        String string2 = string.replace('_', ' ').replace('-', ' ').replaceAll("[^\\p{L}\\p{N} ()/+. ]", " ").replaceAll("\\s+", " ").trim();
        if (string2.equalsIgnoreCase("aim ml")) {
            return "AimBot / ML Aim";
        }
        if (string2.toLowerCase(Locale.ROOT).contains("hitbox")) {
            return "Hitboxes";
        }
        if (string2.equalsIgnoreCase("aim")) {
            return "AimBot / KillAura rotations";
        }
        if (string2.equalsIgnoreCase("reach")) {
            return "Reach";
        }
        return string2;
    }

    private static InetAddress clientAddress(Player player) {
        InetSocketAddress inetSocketAddress = player.getAddress();
        return inetSocketAddress == null ? null : inetSocketAddress.getAddress();
    }

    private static boolean validIpForBan(InetAddress inetAddress, boolean bl) {
        return inetAddress != null && !inetAddress.isAnyLocalAddress() && !inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress() && !inetAddress.isMulticastAddress() && (bl || !inetAddress.isSiteLocalAddress());
    }

    private static String address(InetAddress inetAddress) {
        return inetAddress == null ? "" : inetAddress.getHostAddress();
    }

    private static String encode(String string) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((string == null ? "" : string).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String string) {
        if (string == null || string.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(string), StandardCharsets.UTF_8);
    }

    private static String safeToken(String string) {
        return string == null ? "unknown" : string.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String safeIp(String string) {
        return string == null ? "" : string.replaceAll("[^0-9A-Fa-f:.]", "");
    }

    private static String safeReason(String string) {
        return string == null ? "" : string.replaceAll("[^\\p{L}\\p{N} _+./():-]", " ").replaceAll("\\s+", " ").trim();
    }

    private static void append(Deque<Long> deque, long l, long l2) {
        deque.addLast(l);
        PunishmentManager.prune(deque, l, l2);
    }

    private static void prune(Deque<Long> deque, long l, long l2) {
        while (!deque.isEmpty() && l - deque.peekFirst() > l2) {
            deque.removeFirst();
        }
    }

    private static final class Track {
        private final Deque<Long> nativeGeometry = new ArrayDeque<>();
        private final Deque<Long> consensus = new ArrayDeque<Long>();
        private final Deque<Long> wallHits = new ArrayDeque<Long>();
        private final Deque<Long> remoteActions = new ArrayDeque<Long>();
        private final Deque<Long> mobCombat = new ArrayDeque<Long>();
        private final Set<String> hacks = new LinkedHashSet<String>();
        private long lastAction;

        private Track() {
        }
    }

    private record Sanction(int stage, long bannedUntil, String lastName, String hacks, String ip) {
    }
}

