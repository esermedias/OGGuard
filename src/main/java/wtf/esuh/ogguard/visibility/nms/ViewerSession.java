package wtf.esuh.ogguard.visibility.nms;

import io.netty.channel.Channel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import wtf.esuh.ogguard.visibility.policy.ClientChunkLedger;
import wtf.esuh.ogguard.visibility.policy.TickCache;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityPolicy;
import wtf.esuh.ogguard.visibility.policy.VisibleContainers;
import wtf.esuh.ogguard.visibility.policy.WithheldContainers;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Shared state between one player's scheduler task (writer of {@link #state()}) and that player's Netty handler
 * (reader). Only immutable values cross threads: the {@link ViewerState} snapshot and the policy.
 */
public final class ViewerSession {

    private final UUID playerId;
    private final Channel channel;
    private final Supplier<VisibilityPolicy> policy;
    private final AtomicReference<ViewerState> state = new AtomicReference<>(ViewerState.unknown());
    private final ClientChunkLedger ledger = new ClientChunkLedger();
    /** Identity set (packets do not override equals) of chunk packets sent by the resyncer. */
    private final Set<ClientboundLevelChunkWithLightPacket> resyncPackets =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicReference<VisibleContainers> visibleContainers =
            new AtomicReference<>(VisibleContainers.none());
    private final WithheldContainers withheldContainers = new WithheldContainers();
    /** Line-of-sight results per chunk for the current tick; only touched by the player's scheduler tasks. */
    private final TickCache<Map<Long, Boolean>> containerSightCache = new TickCache<>();
    private volatile GuardChannelHandler handler;

    public ViewerSession(UUID playerId, Channel channel, Supplier<VisibilityPolicy> policy) {
        this.playerId = playerId;
        this.channel = channel;
        this.policy = policy;
    }

    public UUID playerId() {
        return playerId;
    }

    public Channel channel() {
        return channel;
    }

    public VisibilityPolicy policy() {
        return policy.get();
    }

    public ViewerState state() {
        return state.get();
    }

    /** Publishes a new snapshot and returns the previous one. */
    public ViewerState publish(ViewerState next) {
        return state.getAndSet(next);
    }

    /** Containers the viewer may receive block entity data for (written by the region thread). */
    public VisibleContainers visibleContainers() {
        return visibleContainers.get();
    }

    public void publishContainers(VisibleContainers next) {
        visibleContainers.set(next);
    }

    /** Container block entities stripped from packets to this client (written by the event loop). */
    public WithheldContainers withheldContainers() {
        return withheldContainers;
    }

    public TickCache<Map<Long, Boolean>> containerSightCache() {
        return containerSightCache;
    }

    public ClientChunkLedger ledger() {
        return ledger;
    }

    public void markResync(ClientboundLevelChunkWithLightPacket packet) {
        resyncPackets.add(packet);
    }

    /** True (and forgets the mark) when the packet was produced by the resyncer. */
    public boolean consumeResyncMark(ClientboundLevelChunkWithLightPacket packet) {
        return resyncPackets.remove(packet);
    }

    void attach(GuardChannelHandler handler) {
        this.handler = handler;
    }

    /** Asks the handler to re-evaluate hidden/visible entities on its own event loop. */
    public void requestEntityRevalidation() {
        GuardChannelHandler h = handler;
        if (h != null && channel.isActive()) {
            channel.eventLoop().execute(h::revalidateEntities);
        }
    }
}
