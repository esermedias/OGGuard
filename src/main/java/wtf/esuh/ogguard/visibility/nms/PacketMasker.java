package wtf.esuh.ogguard.visibility.nms;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import wtf.esuh.ogguard.visibility.policy.BlockKeys;
import wtf.esuh.ogguard.visibility.policy.ContainerBlockMask;
import wtf.esuh.ogguard.visibility.policy.LightMask;
import wtf.esuh.ogguard.visibility.policy.SectionDecision;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

/**
 * Rewrites outbound block data for one viewer. Never touches the live world: sections are parsed from the packet's
 * own serialized bytes into detached {@link LevelChunkSection} objects and re-serialized with the real section codec.
 *
 * <p>Verified 1.21.11 wire layout (javap of canvas-1.21.11.jar):
 * <ul>
 *   <li>{@code ClientboundLevelChunkWithLightPacket}: int x, int z, chunk data, light data.</li>
 *   <li>{@code ClientboundLevelChunkPacketData}: heightmaps (private codec), varint length + section bytes,
 *       block entity list ({@code BlockEntityInfo.LIST_STREAM_CODEC}). Block entities are separate from sections.</li>
 *   <li>Section bytes: for each section bottom-up, {@code LevelChunkSection.write}: short non-empty count,
 *       block-state {@code PalettedContainer}, biome {@code PalettedContainer}.</li>
 *   <li>{@code BlockEntityInfo.packedXZ = (x &amp; 15) &lt;&lt; 4 | (z &amp; 15)}, {@code y} absolute.</li>
 * </ul>
 * A fresh block-state container is always built for masked sections, so no palette entry of a hidden block
 * survives (a stale palette would otherwise reveal which block types exist in the base).
 */
public final class PacketMasker {

    private final NmsBridge nms;

    public PacketMasker(NmsBridge nms) {
        this.nms = nms;
    }

    // ------------------------------------------------------------------ chunk packets

    /**
     * Decides, for one chunk packet, whether a container block entity is withheld from the viewer (container
     * concealment). Called on the event loop.
     */
    @FunctionalInterface
    public interface ContainerGate {
        boolean hide(String typeId, int x, int y, int z);
    }

    /** Returns the original packet when nothing is masked, otherwise a rebuilt copy. No container concealment. */
    public ClientboundLevelChunkWithLightPacket maskChunk(ClientboundLevelChunkWithLightPacket packet,
                                                          VisibilityPolicy policy, ViewerState viewer,
                                                          ServerLevel level, String world) {
        return maskChunk(packet, policy, viewer, level, world, null, null, false);
    }

    /**
     * Returns the original packet when nothing is masked, otherwise a rebuilt copy.
     *
     * @param gate     container concealment decision, or null to keep every container block entity
     * @param withheld receives the {@link wtf.esuh.ogguard.visibility.policy.BlockKeys} position of every container
     *                 block entity removed by {@code gate}; may be null when {@code gate} is null
     * @param replaceContainers also send the container rule's replacement block instead of the real block state at
     *                 every position whose block entity {@code gate} removed (only those positions change)
     */
    public ClientboundLevelChunkWithLightPacket maskChunk(ClientboundLevelChunkWithLightPacket packet,
                                                          VisibilityPolicy policy, ViewerState viewer,
                                                          ServerLevel level, String world,
                                                          ContainerGate gate, LongConsumer withheld,
                                                          boolean replaceContainers) {
        int cx = packet.getX();
        int cz = packet.getZ();
        int minSection = level.getMinSectionY();
        int sectionCount = level.getSectionsCount();
        SectionDecision[] decisions = new SectionDecision[sectionCount];
        boolean any = false;
        for (int i = 0; i < sectionCount; i++) {
            decisions[i] = policy.sectionDecision(viewer, world, cx, minSection + i, cz);
            any |= decisions[i] != SectionDecision.PASS;
        }
        if (!any && gate == null) {
            return packet;
        }

        ClientboundLevelChunkPacketData data = packet.getChunkData();
        List<?> originalBlockEntities = nms.blockEntities(data);
        LongArrayList removed = new LongArrayList();
        List<?> blockEntities = filterBlockEntities(originalBlockEntities, cx, cz, policy, viewer, world,
                gate, pos -> {
                    removed.add(pos);
                    if (withheld != null) {
                        withheld.accept(pos);
                    }
                });
        int[][] containerMask = replaceContainers
                ? ContainerBlockMask.bySection(cx, cz, minSection, sectionCount, removed.toLongArray())
                : null;
        if (!any && containerMask == null) {
            if (blockEntities == originalBlockEntities) {
                return packet;
            }
            // Only container block entities removed: section bytes and light are forwarded unchanged.
            return rebuildChunk(cx, cz, data, readAll(data.getReadBuffer()), blockEntities, packet.getLightData());
        }
        byte[] sections = rewriteSections(data.getReadBuffer(), decisions, containerMask, minSection, cx, cz,
                policy, viewer, world);
        // Container replacement never touches light: only the underground rule masks it.
        ClientboundLightUpdatePacketData light = any
                ? maskLight(packet.getLightData(), cx, cz, level, policy, viewer, world)
                : packet.getLightData();
        return rebuildChunk(cx, cz, data, sections, blockEntities, light);
    }

    private static byte[] readAll(FriendlyByteBuf in) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        return bytes;
    }

    // Paper deprecates the non-anti-xray overloads; they are exactly the vanilla code paths we want here
    // (no preset values, no anti-xray obfuscation of our own replacement data).
    @SuppressWarnings("deprecation")
    private byte[] rewriteSections(FriendlyByteBuf in, SectionDecision[] decisions, int[][] containerMask,
                                   int minSection, int cx, int cz,
                                   VisibilityPolicy policy, ViewerState viewer, String world) {
        BlockState replacement = nms.replacementState(policy.config().replacement());
        BlockState containerReplacement = nms.replacementState(policy.containerRule().replacementBlock());
        BlockState air = Blocks.AIR.defaultBlockState();
        var strategy = nms.containerFactory().blockStatesStrategy();
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(in.readableBytes()));
        try {
            for (int i = 0; i < decisions.length; i++) {
                int start = in.readerIndex();
                LevelChunkSection parsed = new LevelChunkSection(
                        new PalettedContainer<>(air, strategy), nms.containerFactory().createForBiomes());
                parsed.read(in);
                SectionDecision decision = decisions[i];
                int[] containers = containerMask == null ? null : containerMask[i];
                if (decision == SectionDecision.PASS && containers == null) {
                    out.writeBytes(in, start, in.readerIndex() - start); // byte-identical copy
                    continue;
                }
                PalettedContainer<BlockState> states;
                if (decision == SectionDecision.MASK_ALL) {
                    states = new PalettedContainer<>(replacement, strategy);
                } else {
                    // Partial underground mask and/or withheld containers. A fresh container is built so no palette
                    // entry of a replaced block survives.
                    states = new PalettedContainer<>(air, strategy);
                    boolean partial = decision != SectionDecision.PASS;
                    int baseX = cx << 4;
                    int baseY = (minSection + i) << 4;
                    int baseZ = cz << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState s;
                                if (partial && policy.maskBlock(viewer, world, baseX + x, baseY + y, baseZ + z)) {
                                    s = replacement;
                                } else if (ContainerBlockMask.replaced(containers,
                                        ContainerBlockMask.localIndex(x, y, z))) {
                                    s = containerReplacement;
                                } else {
                                    s = parsed.getBlockState(x, y, z);
                                }
                                if (s != air) {
                                    states.set(x, y, z, s);
                                }
                            }
                        }
                    }
                }
                LevelChunkSection masked = new LevelChunkSection(states, biomesOf(parsed));
                masked.recalcBlockCounts();
                masked.write(out);
            }
            if (in.isReadable()) {
                // Not expected for 1.21.11; refuse rather than forward unknown trailing data.
                throw new IllegalStateException(in.readableBytes() + " unexpected trailing bytes in chunk sections");
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<Holder<Biome>> biomesOf(LevelChunkSection parsed) {
        PalettedContainerRO<Holder<Biome>> ro = parsed.getBiomes();
        if (ro instanceof PalettedContainer<?> rw) {
            return (PalettedContainer<Holder<Biome>>) rw;
        }
        throw new IllegalStateException("Unexpected biome container type " + ro.getClass().getName());
    }

    /** Returns {@code original} itself when nothing was removed. */
    private List<?> filterBlockEntities(List<?> original, int cx, int cz, VisibilityPolicy policy,
                                        ViewerState viewer, String world, ContainerGate gate,
                                        LongConsumer withheld) {
        List<Object> kept = new ArrayList<>(original.size());
        for (Object info : original) {
            int packed = nms.blockEntityPackedXZ(info);
            int x = (cx << 4) + ((packed >> 4) & 15);
            int z = (cz << 4) + (packed & 15);
            int y = nms.blockEntityY(info);
            if (policy.maskBlockEntity(viewer, world, x, y, z)) {
                continue;
            }
            if (gate != null && gate.hide(nms.blockEntityTypeId(info), x, y, z)) {
                if (withheld != null) {
                    withheld.accept(BlockKeys.key(x, y, z));
                }
                continue;
            }
            kept.add(info);
        }
        return kept.size() == original.size() ? original : kept;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ClientboundLevelChunkWithLightPacket rebuildChunk(int cx, int cz, ClientboundLevelChunkPacketData data,
                                                              byte[] sections, List<?> blockEntities,
                                                              ClientboundLightUpdatePacketData light) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(sections.length + 4096),
                nms.registryAccess());
        try {
            buf.writeInt(cx);
            buf.writeInt(cz);
            ((StreamCodec) nms.heightmapsCodec()).encode(buf, (Map) data.getHeightmaps());
            buf.writeVarInt(sections.length);
            buf.writeBytes(sections);
            ((StreamCodec) nms.blockEntityListCodec()).encode(buf, blockEntities);
            light.write(buf);
            ClientboundLevelChunkWithLightPacket rebuilt = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
            rebuilt.setReady(true); // the decode constructor leaves Paper's anti-xray "ready" flag false
            return rebuilt;
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------------ light

    public ClientboundLightUpdatePacket maskLightPacket(ClientboundLightUpdatePacket packet, ServerLevel level,
                                                        VisibilityPolicy policy, ViewerState viewer, String world) {
        ClientboundLightUpdatePacketData original = packet.getLightData();
        ClientboundLightUpdatePacketData masked = maskLight(original, packet.getX(), packet.getZ(), level,
                policy, viewer, world);
        if (masked == original) {
            return packet;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(packet.getX());
            buf.writeInt(packet.getZ());
            masked.write(buf);
            return ClientboundLightUpdatePacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private ClientboundLightUpdatePacketData maskLight(ClientboundLightUpdatePacketData ld, int cx, int cz,
                                                       ServerLevel level, VisibilityPolicy policy,
                                                       ViewerState viewer, String world) {
        if (!policy.config().maskLight()) {
            return ld;
        }
        int minLight = level.getLightEngine().getMinLightSection();
        int lightCount = level.getLightEngine().getLightSectionCount();
        IntPredicate masked = i -> policy.maskLightSection(viewer, world, cx, minLight + i, cz);
        if (!LightMask.affects(ld.getSkyYMask(), masked, lightCount)
                && !LightMask.affects(ld.getBlockYMask(), masked, lightCount)) {
            return ld;
        }
        LightMask.Layer sky = LightMask.apply(ld.getSkyYMask(), ld.getEmptySkyYMask(), ld.getSkyUpdates(),
                masked, lightCount);
        LightMask.Layer block = LightMask.apply(ld.getBlockYMask(), ld.getEmptyBlockYMask(), ld.getBlockUpdates(),
                masked, lightCount);
        // Same order as ClientboundLightUpdatePacketData.write (verified with javap).
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBitSet(sky.dataMask());
            buf.writeBitSet(block.dataMask());
            buf.writeBitSet(sky.emptyMask());
            buf.writeBitSet(block.emptyMask());
            StreamCodec<ByteBuf, byte[]> layer = nms.dataLayerCodec();
            buf.writeVarInt(sky.data().size());
            for (byte[] arr : sky.data()) {
                layer.encode(buf, arr);
            }
            buf.writeVarInt(block.data().size());
            for (byte[] arr : block.data()) {
                layer.encode(buf, arr);
            }
            return new ClientboundLightUpdatePacketData(buf, cx, cz);
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------------ block updates

    /**
     * Container block replacement for single block updates (event loop). Implemented by the channel handler, which
     * owns the withheld bookkeeping.
     */
    @FunctionalInterface
    public interface ContainerUpdateGate {
        /** Returns the state to send at this position (the given state itself when nothing changes). */
        BlockState state(int x, int y, int z, BlockState state);
    }

    public ClientboundBlockUpdatePacket maskBlockUpdate(ClientboundBlockUpdatePacket packet, VisibilityPolicy policy,
                                                        ViewerState viewer, String world) {
        return maskBlockUpdate(packet, policy, viewer, world, null);
    }

    public ClientboundBlockUpdatePacket maskBlockUpdate(ClientboundBlockUpdatePacket packet, VisibilityPolicy policy,
                                                        ViewerState viewer, String world,
                                                        ContainerUpdateGate containers) {
        BlockPos pos = packet.getPos();
        if (!policy.maskBlock(viewer, world, pos.getX(), pos.getY(), pos.getZ())) {
            if (containers == null) {
                return packet;
            }
            BlockState sent = containers.state(pos.getX(), pos.getY(), pos.getZ(), packet.getBlockState());
            return sent == packet.getBlockState() ? packet : new ClientboundBlockUpdatePacket(pos.immutable(), sent);
        }
        BlockState replacement = nms.replacementState(policy.config().replacement());
        if (packet.getBlockState() == replacement) {
            return packet;
        }
        return new ClientboundBlockUpdatePacket(pos.immutable(), replacement);
    }

    public ClientboundSectionBlocksUpdatePacket maskSectionUpdate(ClientboundSectionBlocksUpdatePacket packet,
                                                                  VisibilityPolicy policy, ViewerState viewer,
                                                                  String world) {
        return maskSectionUpdate(packet, policy, viewer, world, null);
    }

    public ClientboundSectionBlocksUpdatePacket maskSectionUpdate(ClientboundSectionBlocksUpdatePacket packet,
                                                                  VisibilityPolicy policy, ViewerState viewer,
                                                                  String world, ContainerUpdateGate containers) {
        BlockState replacement = nms.replacementState(policy.config().replacement());
        Short2ObjectMap<BlockState> states = new Short2ObjectOpenHashMap<>();
        SectionPos[] section = new SectionPos[1];
        boolean[] changed = new boolean[1];
        packet.runUpdates((pos, state) -> {
            if (section[0] == null) {
                section[0] = SectionPos.of(pos);
            }
            BlockState sent = state;
            if (policy.maskBlock(viewer, world, pos.getX(), pos.getY(), pos.getZ())) {
                if (state != replacement) {
                    sent = replacement;
                    changed[0] = true;
                }
            } else if (containers != null) {
                sent = containers.state(pos.getX(), pos.getY(), pos.getZ(), state);
                changed[0] |= sent != state;
            }
            states.put(SectionPos.sectionRelativePos(pos), sent);
        });
        if (!changed[0] || section[0] == null) {
            return packet;
        }
        return new ClientboundSectionBlocksUpdatePacket(section[0], states);
    }
}
