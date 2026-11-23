package wtf.esuh.ogguard.platform;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import wtf.esuh.ogguard.api.GuardApi;
import wtf.esuh.ogguard.visibility.nms.GuardChannelHandler;
import wtf.esuh.ogguard.visibility.nms.NmsBridge;
import wtf.esuh.ogguard.visibility.nms.PacketMasker;
import wtf.esuh.ogguard.visibility.nms.ViewerSession;
import wtf.esuh.ogguard.visibility.policy.ChunkKeys;
import wtf.esuh.ogguard.visibility.policy.ContainerPlanner;
import wtf.esuh.ogguard.visibility.policy.ContainerRule;
import wtf.esuh.ogguard.visibility.policy.ContainerSight;
import wtf.esuh.ogguard.visibility.policy.Cuboid;
import wtf.esuh.ogguard.visibility.policy.ResyncPlan;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityConfig;
import wtf.esuh.ogguard.visibility.policy.VisibilityPolicy;
import wtf.esuh.ogguard.visibility.policy.VisibleContainers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Folia-native wiring of the packet visibility system.
 *
 * <ul>
 *   <li>Player state is computed only on the player's own region thread (entity scheduler) and published as an
 *       immutable snapshot.</li>
 *   <li>Chunks are read and resend packets built only on the region thread owning the chunk (region scheduler);
 *       the live world is never modified.</li>
 *   <li>The Netty handler only reads immutable snapshots.</li>
 * </ul>
 */
public final class VisibilityService implements GuardApi {

    public static final String BYPASS_PERMISSION = "ogguard.bypass";

    private final Plugin plugin;
    private final NmsBridge nms;
    private final PacketMasker masker;
    private final long periodTicks;
    private final Map<UUID, ViewerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Cuboid> apiRegions = new ConcurrentHashMap<>();
    private final AtomicReference<VisibilityConfig> config = new AtomicReference<>();
    private final AtomicReference<ContainerRule> containerRule = new AtomicReference<>();
    private final AtomicReference<VisibilityPolicy> policy = new AtomicReference<>();

    public VisibilityService(Plugin plugin, NmsBridge nms, VisibilityConfig initial, ContainerRule containers,
                             long periodTicks) {
        this.plugin = plugin;
        this.nms = nms;
        this.masker = new PacketMasker(nms);
        this.periodTicks = Math.max(1, periodTicks);
        this.config.set(initial);
        this.containerRule.set(containers);
        this.policy.set(new VisibilityPolicy(initial, new ArrayList<>(apiRegions.values()), containers));
    }

    public VisibilityPolicy policy() {
        return policy.get();
    }

    /** Runs {@link MaskingSelfTest} on the region thread owning the chunk and reports to the consumer. */
    public void selfTest(World world, int cx, int cz, java.util.function.Consumer<String> out) {
        world.getChunkAtAsync(cx, cz).thenAccept(loaded ->
                Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                    try {
                        MaskingSelfTest.run(nms, policy.get(), world, cx, cz).forEach(out);
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(Level.SEVERE, "Self test failed with an exception", e);
                        out.accept("FAIL exception: " + e);
                    }
                }));
    }

    public int sessionCount() {
        return sessions.size();
    }

    public ViewerSession session(UUID id) {
        return sessions.get(id);
    }

    // ------------------------------------------------------------------ lifecycle (player region thread)

    /** Must run on the player's region thread (join event or entity scheduler). */
    public void attach(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        Channel channel = handle.connection.connection.channel;
        if (channel == null) {
            return;
        }
        ViewerSession session = new ViewerSession(player.getUniqueId(), channel, policy::get);
        session.publish(computeState(player, null));
        GuardChannelHandler handler = new GuardChannelHandler(session, handle, handle.level(), nms, masker,
                plugin.getLogger());
        ChannelPipeline pipeline = channel.pipeline();
        try {
            if (pipeline.get(GuardChannelHandler.NAME) != null) {
                pipeline.remove(GuardChannelHandler.NAME);
            }
            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", GuardChannelHandler.NAME, handler);
            } else {
                pipeline.addLast(GuardChannelHandler.NAME, handler);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not install packet visibility handler for "
                    + player.getName() + "; disconnecting (fail closed).", e);
            player.kick(net.kyori.adventure.text.Component.text("Visibility guard unavailable, please rejoin."));
            return;
        }
        sessions.put(player.getUniqueId(), session);
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (sessions.get(player.getUniqueId()) != session) {
                task.cancel();
                return;
            }
            tick(player, session);
        }, null, 1L, periodTicks);
        scheduleContainerCheck(player, session, 1L);
    }

    public void detach(Player player) {
        ViewerSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        Channel channel = session.channel();
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(GuardChannelHandler.NAME) != null) {
                channel.pipeline().remove(GuardChannelHandler.NAME);
            }
        });
    }

    public void detachAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            detach(player);
        }
        sessions.clear();
    }

    private ViewerState computeState(Player player, ViewerState previous) {
        Location loc = player.getLocation();
        return policy.get().nextState(previous, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                player.hasPermission(BYPASS_PERMISSION));
    }

    /** Runs on the player's region thread. */
    void tick(Player player, ViewerSession session) {
        ViewerState previous = session.state();
        ViewerState next = computeState(player, previous);
        session.publish(next);
        ResyncPlan plan = policy.get().resyncPlan(previous, next, session.ledger().snapshot());
        if (plan.revealChanged()) {
            session.requestEntityRevalidation();
        }
        if (!plan.chunks().isEmpty()) {
            resync(player, session, player.getWorld(), plan.chunks());
        }
    }

    /** Force a state refresh soon (teleport, world change). Safe from any thread. */
    public void refreshSoon(Player player) {
        ViewerSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            player.getScheduler().run(plugin, t -> tick(player, session), null);
        }
    }

    // ------------------------------------------------------------------ container concealment (player region thread)

    /** Self-rescheduling so a reloaded check-interval-ticks applies without a rejoin. */
    private void scheduleContainerCheck(Player player, ViewerSession session, long delay) {
        player.getScheduler().runDelayed(plugin, task -> {
            if (sessions.get(player.getUniqueId()) != session) {
                return;
            }
            try {
                checkContainers(player, session);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Container visibility check failed for " + player.getName(), e);
            } finally {
                scheduleContainerCheck(player, session, policy.get().containerRule().checkIntervalTicks());
            }
        }, null, Math.max(1L, delay));
    }

    /**
     * Runs on the player's region thread: ray-casts the withheld (and previously visible) containers near the
     * player, publishes the visible set for the Netty handler and resends chunks whose withheld container became
     * visible, so the client receives the block entity through the normal (masked) chunk path.
     */
    void checkContainers(Player player, ViewerSession session) {
        VisibilityPolicy pol = policy.get();
        ViewerState viewer = session.state();
        Map<Long, long[]> withheld = session.withheldContainers().snapshot();
        World world = player.getWorld();
        if (!pol.containerRuleActive(viewer) || !pol.worldCovered(world.getName())) {
            // Rule off, bypass or uncovered world: the handler no longer withholds; deliver what was withheld.
            session.publishContainers(VisibleContainers.none());
            if (!withheld.isEmpty()) {
                resendHeld(player, session, world, withheld.keySet());
            }
            return;
        }
        if (withheld.isEmpty() && session.visibleContainers().positions().isEmpty()) {
            return;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        Location eye = player.getEyeLocation();
        Map<Long, LevelChunk> chunks = new HashMap<>();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        ContainerSight.Solidity solid = (x, y, z) -> {
            if (y < minY || y > maxY) {
                return false;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            long key = ChunkKeys.key(cx, cz);
            LevelChunk chunk;
            if (chunks.containsKey(key)) {
                chunk = chunks.get(key);
            } else {
                // Folia: only read chunks owned by this region; anything else blocks the view (fail closed).
                chunk = Bukkit.isOwnedByCurrentRegion(world, cx, cz) ? level.getChunkSource().getChunkNow(cx, cz) : null;
                chunks.put(key, chunk);
            }
            return chunk == null || chunk.getBlockState(x, y, z).isSolidRender();
        };
        ContainerPlanner.Result result = ContainerPlanner.plan(pol.containerRule(), eye.getX(), eye.getY(),
                eye.getZ(), withheld, session.visibleContainers().positionsIn(world.getName()), solid,
                session.containerSightCache(), level.getGameTime());
        session.publishContainers(new VisibleContainers(world.getName(), result.visible()));
        if (!result.resendChunks().isEmpty()) {
            resendHeld(player, session, world, result.resendChunks());
        }
    }

    /** Resends chunks the client still holds; forgets withheld entries of chunks it no longer has. */
    private void resendHeld(Player player, ViewerSession session, World world, Set<Long> chunks) {
        Set<Long> held = session.ledger().snapshot();
        Set<Long> keys = new HashSet<>();
        for (long key : chunks) {
            if (held.contains(key)) {
                keys.add(key);
            } else {
                session.withheldContainers().forgetChunk(ChunkKeys.x(key), ChunkKeys.z(key));
            }
        }
        if (!keys.isEmpty()) {
            resync(player, session, world, keys);
        }
    }

    // ------------------------------------------------------------------ resync (chunk region thread)

    private void resync(Player player, ViewerSession session, World world, Set<Long> chunks) {
        for (long key : chunks) {
            int cx = ChunkKeys.x(key);
            int cz = ChunkKeys.z(key);
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> resendChunk(player, session, world, cx, cz));
        }
    }

    /** Runs on the region thread that owns the chunk. Reads the chunk; never modifies it. */
    private void resendChunk(Player player, ViewerSession session, World world, int cx, int cz) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return; // not loaded: the client cannot hold it either, normal chunk sending will handle it
        }
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        // Same construction as PlayerChunkSender.sendChunk (verified with javap), including Paper anti-xray.
        boolean modifyBlocks = level.chunkPacketBlockController.shouldModify(handle, chunk);
        ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, modifyBlocks);
        session.markResync(packet); // handler drops it if the client forgot the chunk meanwhile
        handle.connection.send(packet);
    }

    // ------------------------------------------------------------------ policy changes

    public void reload(VisibilityConfig newConfig, ContainerRule newContainers) {
        config.set(newConfig);
        containerRule.set(newContainers);
        rebuildPolicy();
    }

    private void rebuildPolicy() {
        VisibilityPolicy old = policy.get();
        VisibilityPolicy fresh = new VisibilityPolicy(config.get(), new ArrayList<>(apiRegions.values()),
                containerRule.get());
        policy.set(fresh);
        for (Player player : Bukkit.getOnlinePlayers()) {
            ViewerSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                continue;
            }
            player.getScheduler().run(plugin, t -> fullResync(player, session, old, fresh), null);
        }
    }

    /** Player region thread: resend every held chunk whose protection may have changed. */
    private void fullResync(Player player, ViewerSession session, VisibilityPolicy old, VisibilityPolicy fresh) {
        ViewerState next = computeState(player, session.state());
        session.publish(next);
        String world = player.getWorld().getName();
        Set<Long> keys = new HashSet<>();
        for (long key : session.ledger().snapshot()) {
            int cx = ChunkKeys.x(key);
            int cz = ChunkKeys.z(key);
            if (old.chunkHasProtection(world, cx, cz) || fresh.chunkHasProtection(world, cx, cz)
                    || old.enabled() != fresh.enabled()) {
                keys.add(key);
            }
        }
        session.requestEntityRevalidation();
        if (!keys.isEmpty()) {
            resync(player, session, player.getWorld(), keys);
        }
    }

    // ------------------------------------------------------------------ GuardApi

    @Override
    public void putProtectedRegion(String key, Cuboid region) {
        apiRegions.put(key, region);
        rebuildPolicy();
    }

    @Override
    public boolean removeProtectedRegion(String key) {
        boolean removed = apiRegions.remove(key) != null;
        if (removed) {
            rebuildPolicy();
        }
        return removed;
    }

    @Override
    public Map<String, Cuboid> protectedRegions() {
        return Map.copyOf(new LinkedHashMap<>(apiRegions));
    }
}
