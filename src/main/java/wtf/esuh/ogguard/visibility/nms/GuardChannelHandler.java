package wtf.esuh.ogguard.visibility.nms;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import wtf.esuh.ogguard.visibility.policy.ContainerBlockMask;
import wtf.esuh.ogguard.visibility.policy.EntityViewTracker;
import wtf.esuh.ogguard.visibility.policy.EntityViewTracker.Transition;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityPolicy;
import wtf.esuh.ogguard.visibility.policy.VisibleContainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-connection outbound handler. Installed before Minecraft's {@code packet_handler} so it sees whole
 * {@link Packet} objects (bundles intact) before the unbundler and encoder serialize them.
 *
 * <p>Threading: every method runs on this channel's Netty event loop. It never calls Bukkit or touches the live
 * world: decisions use only the immutable {@link VisibilityPolicy} and the {@link ViewerState} snapshot published by
 * the player's own scheduler task. The {@link EntityViewTracker} is owned by this handler and confined to the loop.
 *
 * <p>Failure mode is closed: if a rewrite throws, the packet is dropped (and logged, rate-limited) rather than sent
 * unmasked. A dropped chunk stays in the ledger, so the next resync for that chunk retries it.
 */
public final class GuardChannelHandler extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "ogguard";
    private static final long FAILURE_LOG_INTERVAL_NANOS = 10_000_000_000L;

    private final ViewerSession session;
    private final ServerPlayer player;
    private final NmsBridge nms;
    private final PacketMasker masker;
    private final Logger logger;
    private final EntityViewTracker<EntityReplay> entities;
    private ChannelHandlerContext ctx;
    /** The level the client currently has loaded, tracked in send order from respawn packets. */
    private ServerLevel clientLevel;
    private long lastFailureLog;

    public GuardChannelHandler(ViewerSession session, ServerPlayer player, ServerLevel initialLevel,
                               NmsBridge nms, PacketMasker masker, Logger logger) {
        this.session = session;
        this.player = player;
        this.nms = nms;
        this.masker = masker;
        this.logger = logger;
        this.clientLevel = initialLevel;
        this.entities = new EntityViewTracker<>(player.getId());
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        session.attach(this);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            ctx.write(msg, promise);
            return;
        }
        Packet<?> out;
        try {
            out = process(packet);
        } catch (Throwable t) {
            logFailure(packet, t);
            out = null; // fail closed
        }
        if (out == null) {
            promise.trySuccess();
            return;
        }
        ctx.write(out, promise);
    }

    // ------------------------------------------------------------------ dispatch

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Packet<?> process(Packet<?> packet) {
        Context c = context();
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<?> result = single(sub, c);
                if (result != sub) {
                    changed = true;
                }
                if (result instanceof ClientboundBundlePacket inner) {
                    inner.subPackets().forEach(list::add);
                } else if (result != null) {
                    list.add((Packet) result);
                }
            }
            if (!changed) {
                return bundle;
            }
            return list.isEmpty() ? null : new ClientboundBundlePacket(list);
        }
        return single(packet, c);
    }

    private record Context(VisibilityPolicy policy, ViewerState viewer, ServerLevel level, String world,
                           EntityViewTracker.HideRule rule) {
    }

    private Context context() {
        VisibilityPolicy policy = session.policy();
        ViewerState viewer = session.state();
        ServerLevel level = clientLevel != null ? clientLevel : player.level();
        String world = level.getWorld().getName();
        EntityViewTracker.HideRule rule = policy.worldExcluded(world)
                ? (x, y, z, isPlayer) -> false
                : (x, y, z, isPlayer) -> policy.hideEntity(viewer, world, x, y, z, isPlayer);
        return new Context(policy, viewer, level, world, rule);
    }

    private Packet<?> single(Packet<?> p, Context c) {
        VisibilityPolicy policy = c.policy();
        // Excluded worlds (packet-visibility.excluded-worlds) are never touched: bookkeeping only, packets unchanged.
        boolean on = policy.enabled() && !policy.worldExcluded(c.world());

        // --- client world bookkeeping (always, so the ledger is right if the guard is enabled later)
        if (p instanceof ClientboundLevelChunkWithLightPacket chunk) {
            int cx = chunk.getX();
            int cz = chunk.getZ();
            if (session.consumeResyncMark(chunk) && !session.ledger().admitResync(cx, cz)) {
                return null; // client already forgot this chunk: a resync now would create a ghost chunk
            }
            session.ledger().onChunkSent(cx, cz);
            if (!on) {
                session.withheldContainers().forgetChunk(cx, cz);
                return chunk;
            }
            return maskChunk(chunk, c);
        }
        if (p instanceof ClientboundForgetLevelChunkPacket forget) {
            session.ledger().onForget(forget.pos().x, forget.pos().z);
            session.withheldContainers().forgetChunk(forget.pos().x, forget.pos().z);
            return p;
        }
        if (p instanceof ClientboundRespawnPacket respawn) {
            session.ledger().clear();
            session.withheldContainers().clear();
            entities.clear();
            clientLevel = levelFor(respawn);
            return p;
        }
        if (p instanceof ClientboundStartConfigurationPacket) {
            session.ledger().clear();
            session.withheldContainers().clear();
            entities.clear();
            return p;
        }

        // --- entities (tracked always; hidden only when the policy says so)
        Packet<?> entityResult = entity(p, c);
        if (entityResult != NOT_ENTITY) {
            return entityResult;
        }
        if (!on) {
            return p;
        }

        // --- block data
        if (p instanceof ClientboundLightUpdatePacket light) {
            return masker.maskLightPacket(light, c.level(), policy, c.viewer(), c.world());
        }
        if (p instanceof ClientboundBlockUpdatePacket update) {
            return masker.maskBlockUpdate(update, policy, c.viewer(), c.world(), containerUpdateGate(c));
        }
        if (p instanceof ClientboundSectionBlocksUpdatePacket update) {
            return masker.maskSectionUpdate(update, policy, c.viewer(), c.world(), containerUpdateGate(c));
        }
        if (p instanceof ClientboundBlockEntityDataPacket be) {
            BlockPos pos = be.getPos();
            if (policy.maskBlockEntity(c.viewer(), c.world(), pos.getX(), pos.getY(), pos.getZ())) {
                return null;
            }
            if (policy.hideContainer(c.viewer(), session.visibleContainers(), c.world(), nms.typeId(be.getType()),
                    pos.getX(), pos.getY(), pos.getZ())) {
                // Delivered later by a chunk resend once the container is in range and line of sight.
                session.withheldContainers().add(pos.getX(), pos.getY(), pos.getZ());
                return null;
            }
            session.withheldContainers().remove(pos.getX(), pos.getY(), pos.getZ());
            return p;
        }

        // --- positional side effects
        if (p instanceof ClientboundBlockEventPacket e) {
            return maskedAt(e.getPos(), c) ? null : p;
        }
        if (p instanceof ClientboundBlockDestructionPacket e) {
            return maskedAt(e.getPos(), c) ? null : p;
        }
        if (p instanceof ClientboundLevelEventPacket e) {
            return !e.isGlobalEvent() && maskedAt(e.getPos(), c) ? null : p;
        }
        if (p instanceof ClientboundSoundPacket e) {
            return policy.maskEffect(c.viewer(), c.world(), e.getX(), e.getY(), e.getZ()) ? null : p;
        }
        if (p instanceof ClientboundLevelParticlesPacket e) {
            return policy.maskEffect(c.viewer(), c.world(), e.getX(), e.getY(), e.getZ()) ? null : p;
        }
        return p;
    }

    /** Masks a chunk packet and records which container block entities were withheld from it. */
    private Packet<?> maskChunk(ClientboundLevelChunkWithLightPacket chunk, Context c) {
        VisibilityPolicy policy = c.policy();
        PacketMasker.ContainerGate gate = null;
        LongArrayList withheld = new LongArrayList();
        if (policy.containerRuleActive(c.viewer())) {
            VisibleContainers visible = session.visibleContainers();
            gate = (type, x, y, z) -> policy.hideContainer(c.viewer(), visible, c.world(), type, x, y, z);
        }
        boolean replace = gate != null && policy.containerRule().replaceBlock();
        ClientboundLevelChunkWithLightPacket out = masker.maskChunk(chunk, policy, c.viewer(), c.level(), c.world(),
                gate, withheld::add, replace);
        session.withheldContainers().setChunk(chunk.getX(), chunk.getZ(), withheld.toLongArray());
        return out;
    }

    /**
     * Container block replacement for block updates, or null when it does not apply to this viewer. Keeps the
     * withheld record in step with what the client holds: a position sent as the replacement is withheld (so the
     * planner resends its chunk once it is visible), a position that no longer holds a container is released.
     */
    private PacketMasker.ContainerUpdateGate containerUpdateGate(Context c) {
        VisibilityPolicy policy = c.policy();
        if (!policy.containerRuleActive(c.viewer()) || !policy.containerRule().replaceBlock()) {
            return null;
        }
        VisibleContainers visible = session.visibleContainers();
        BlockState replacement = nms.replacementState(policy.containerRule().replacementBlock());
        return (x, y, z, state) -> {
            String type = nms.blockEntityTypeIdOf(state);
            boolean container = type != null && policy.containerRule().matches(type);
            boolean hidden = container && policy.hideContainer(c.viewer(), visible, c.world(), type, x, y, z);
            boolean withheld = session.withheldContainers().contains(x, y, z);
            ContainerBlockMask.UpdateAction action = ContainerBlockMask.blockUpdate(true, container, hidden,
                    withheld, ContainerBlockMask.near(c.viewer(), c.world(), x, y, z));
            return switch (action) {
                case PASS -> state;
                case PASS_AND_RELEASE -> {
                    session.withheldContainers().remove(x, y, z);
                    yield state;
                }
                case REPLACE -> replacement;
                case REPLACE_AND_WITHHOLD -> {
                    session.withheldContainers().add(x, y, z);
                    yield replacement;
                }
            };
        };
    }

    /**
     * Level named by a respawn packet. The server's level map is read from the event loop, so a world being loaded
     * or unloaded at the same moment must not drop the respawn packet: fall back to the player's current level.
     */
    private ServerLevel levelFor(ClientboundRespawnPacket respawn) {
        try {
            ServerLevel next = MinecraftServer.getServer().getLevel(respawn.commonPlayerSpawnInfo().dimension());
            if (next != null) {
                return next;
            }
        } catch (RuntimeException ignored) {
            // concurrent world list change; use the fallback below
        }
        return player.level();
    }

    private static boolean maskedAt(BlockPos pos, Context c) {
        return c.policy().maskEffect(c.viewer(), c.world(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ------------------------------------------------------------------ entities

    /** Sentinel: the packet is not entity-related. */
    private static final Packet<?> NOT_ENTITY = new ClientboundRemoveEntitiesPacket(new IntArrayList());

    private Packet<?> entity(Packet<?> p, Context c) {
        EntityViewTracker.HideRule rule = c.rule();
        if (p instanceof ClientboundAddEntityPacket add) {
            boolean hidden = entities.onSpawn(add.getId(), add.getX(), add.getY(), add.getZ(),
                    nms.isPlayerSpawn(add), new EntityReplay(add), rule);
            return hidden ? null : p;
        }
        if (p instanceof ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach(entities::onRemove);
            return p;
        }
        if (p instanceof ClientboundTeleportEntityPacket tp) {
            EntityViewTracker.Tracked<EntityReplay> t = entities.get(tp.id());
            if (t == null) {
                return p;
            }
            Vec3 pos = tp.change().position();
            Set<Relative> rel = tp.relatives();
            double x = rel.contains(Relative.X) ? t.x() + pos.x : pos.x;
            double y = rel.contains(Relative.Y) ? t.y() + pos.y : pos.y;
            double z = rel.contains(Relative.Z) ? t.z() + pos.z : pos.z;
            return transition(entities.onAbsoluteMove(tp.id(), x, y, z, rule), tp.id(), p);
        }
        if (p instanceof ClientboundEntityPositionSyncPacket sync) {
            Vec3 pos = sync.values().position();
            return transition(entities.onAbsoluteMove(sync.id(), pos.x, pos.y, pos.z, rule), sync.id(), p);
        }
        if (p instanceof ClientboundMoveEntityPacket move) {
            int id = nms.moveEntityId(move);
            if (!move.hasPosition()) {
                return dropIfHidden(id, p);
            }
            // VecDeltaCodec: delta = encoded / 4096.0 (verified with javap)
            return transition(entities.onRelativeMove(id, move.getXa() / 4096.0, move.getYa() / 4096.0,
                    move.getZa() / 4096.0, rule), id, p);
        }
        if (p instanceof ClientboundSetEntityDataPacket data) {
            EntityReplay r = entities.payload(data.id());
            if (r != null) {
                r.onMetadata(data);
            }
            return dropIfHidden(data.id(), p);
        }
        if (p instanceof ClientboundSetEquipmentPacket eq) {
            EntityReplay r = entities.payload(eq.getEntity());
            if (r != null) {
                r.onEquipment(eq);
            }
            return dropIfHidden(eq.getEntity(), p);
        }
        if (p instanceof ClientboundUpdateAttributesPacket attr) {
            EntityReplay r = entities.payload(attr.getEntityId());
            if (r != null) {
                r.onAttributes(attr);
            }
            return dropIfHidden(attr.getEntityId(), p);
        }
        if (p instanceof ClientboundSetPassengersPacket pass) {
            EntityReplay r = entities.payload(pass.getVehicle());
            if (r != null) {
                r.onPassengers(pass);
            }
            return dropIfHidden(pass.getVehicle(), p);
        }
        if (p instanceof ClientboundSetEntityMotionPacket e) {
            return dropIfHidden(e.getId(), p);
        }
        if (p instanceof ClientboundRotateHeadPacket e) {
            return dropIfHidden(nms.rotateHeadEntityId(e), p);
        }
        if (p instanceof ClientboundEntityEventPacket e) {
            return dropIfHidden(nms.entityEventEntityId(e), p);
        }
        if (p instanceof ClientboundAnimatePacket e) {
            return dropIfHidden(e.getId(), p);
        }
        if (p instanceof ClientboundHurtAnimationPacket e) {
            return dropIfHidden(e.id(), p);
        }
        if (p instanceof ClientboundDamageEventPacket e) {
            return dropIfHidden(e.entityId(), p);
        }
        if (p instanceof ClientboundUpdateMobEffectPacket e) {
            return dropIfHidden(e.getEntityId(), p);
        }
        if (p instanceof ClientboundRemoveMobEffectPacket e) {
            return dropIfHidden(e.entityId(), p);
        }
        if (p instanceof ClientboundTakeItemEntityPacket e) {
            return entities.isHidden(e.getItemId()) || entities.isHidden(e.getPlayerId()) ? null : p;
        }
        if (p instanceof ClientboundSetEntityLinkPacket e) {
            return dropIfHidden(e.getSourceId(), p);
        }
        if (p instanceof ClientboundSoundEntityPacket e) {
            return dropIfHidden(e.getId(), p);
        }
        if (p instanceof ClientboundProjectilePowerPacket e) {
            return dropIfHidden(e.getId(), p);
        }
        return NOT_ENTITY;
    }

    private Packet<?> dropIfHidden(int id, Packet<?> p) {
        return entities.isHidden(id) ? null : p;
    }

    private Packet<?> transition(Transition transition, int id, Packet<?> original) {
        return switch (transition) {
            case UNKNOWN, STAY_VISIBLE -> original;
            case STAY_HIDDEN -> null;
            case BECAME_HIDDEN -> new ClientboundRemoveEntitiesPacket(id);
            case BECAME_VISIBLE -> replay(id);
        };
    }

    private Packet<?> replay(int id) {
        EntityViewTracker.Tracked<EntityReplay> t = entities.get(id);
        if (t == null) {
            return null;
        }
        return new ClientboundBundlePacket(t.payload().replay(t.x(), t.y(), t.z()));
    }

    /** Runs on the event loop after the viewer snapshot changed. */
    void revalidateEntities() {
        ChannelHandlerContext context = this.ctx;
        if (context == null || !context.channel().isActive()) {
            return;
        }
        try {
            Context c = context();
            EntityViewTracker.Changes changes = entities.revalidate(c.rule());
            if (!changes.hidden().isEmpty()) {
                context.write(new ClientboundRemoveEntitiesPacket(new IntArrayList(changes.hidden())));
            }
            for (int id : changes.shown()) {
                Packet<?> bundle = replay(id);
                if (bundle != null) {
                    context.write(bundle);
                }
            }
            if (!changes.hidden().isEmpty() || !changes.shown().isEmpty()) {
                context.flush();
            }
        } catch (Throwable t) {
            logFailure(null, t);
        }
    }

    private void logFailure(Packet<?> packet, Throwable t) {
        long now = System.nanoTime();
        if (now - lastFailureLog < FAILURE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastFailureLog = now;
        logger.log(Level.WARNING, "Packet visibility rewrite failed for " + player.getScoreboardName()
                + (packet == null ? "" : " on " + packet.getClass().getSimpleName())
                + "; packet dropped (fail closed)", t);
    }
}
