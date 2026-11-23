package wtf.esuh.ogguard.visibility.nms;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import wtf.esuh.ogguard.visibility.policy.Replacement;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Read-only reflective access to the few private members of the 1.21.11 packet classes that have no public
 * accessor. Every member name here was verified with {@code javap -p} against canvas-1.21.11.jar (build #794).
 * No final field is ever written: rewritten packets are rebuilt through their own public decoders.
 *
 * <p>Created once on the server thread at enable; afterwards only read, so it is safe from Netty threads.
 */
public final class NmsBridge {

    private final RegistryAccess registryAccess;
    private final PalettedContainerFactory containerFactory;

    private final Field blockEntitiesData;
    private final StreamCodec<ByteBuf, Map<?, long[]>> heightmapsCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, List<?>> blockEntityListCodec;
    private final Field blockEntityPackedXZ;
    private final Field blockEntityY;
    private final Field blockEntityType;
    private final StreamCodec<ByteBuf, byte[]> dataLayerCodec;
    private final Field moveEntityId;
    private final Field rotateHeadEntityId;
    private final Field entityEventEntityId;

    @SuppressWarnings("unchecked")
    public NmsBridge() throws ReflectiveOperationException {
        this.registryAccess = MinecraftServer.getServer().registryAccess();
        this.containerFactory = PalettedContainerFactory.create(registryAccess);

        this.blockEntitiesData = field(ClientboundLevelChunkPacketData.class, "blockEntitiesData");
        this.heightmapsCodec = (StreamCodec<ByteBuf, Map<?, long[]>>)
                field(ClientboundLevelChunkPacketData.class, "HEIGHTMAPS_STREAM_CODEC").get(null);
        Class<?> info = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
        this.blockEntityListCodec = (StreamCodec<RegistryFriendlyByteBuf, List<?>>)
                field(info, "LIST_STREAM_CODEC").get(null);
        this.blockEntityPackedXZ = field(info, "packedXZ");
        this.blockEntityY = field(info, "y");
        this.blockEntityType = field(info, "type");
        this.dataLayerCodec = (StreamCodec<ByteBuf, byte[]>)
                field(ClientboundLightUpdatePacketData.class, "DATA_LAYER_STREAM_CODEC").get(null);
        this.moveEntityId = field(ClientboundMoveEntityPacket.class, "entityId");
        this.rotateHeadEntityId = field(ClientboundRotateHeadPacket.class, "entityId");
        this.entityEventEntityId = field(ClientboundEntityEventPacket.class, "entityId");
        this.addEntityType = field(ClientboundAddEntityPacket.class, "type");
    }

    private final Field addEntityType;

    /** ClientboundAddEntityPacket has no public type getter in 1.21.11; the private field is read. */
    public boolean isPlayerSpawn(ClientboundAddEntityPacket packet) {
        return get(addEntityType, packet) == EntityType.PLAYER;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    public PalettedContainerFactory containerFactory() {
        return containerFactory;
    }

    public BlockState replacementState(Replacement replacement) {
        return switch (replacement) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            case DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
        };
    }

    public List<?> blockEntities(ClientboundLevelChunkPacketData data) {
        return (List<?>) get(blockEntitiesData, data);
    }

    public int blockEntityPackedXZ(Object info) {
        return (int) get(blockEntityPackedXZ, info);
    }

    public int blockEntityY(Object info) {
        return (int) get(blockEntityY, info);
    }

    /** Namespaced type id (for example {@code minecraft:chest}) of a chunk packet BlockEntityInfo, or null. */
    public String blockEntityTypeId(Object info) {
        return typeId((BlockEntityType<?>) get(blockEntityType, info));
    }

    /** Block to block entity type id ("" for blocks without one); filled lazily, safe from any thread. */
    private final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.Block, String> blockTypeIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Namespaced id of the block entity type a block state carries, or null when it has none. */
    public String blockEntityTypeIdOf(BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        String id = blockTypeIds.computeIfAbsent(state.getBlock(), block -> {
            for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                if (type.validBlocks.contains(block)) {
                    String t = typeId(type);
                    return t == null ? "" : t;
                }
            }
            return "";
        });
        return id.isEmpty() ? null : id;
    }

    /** Namespaced registry id of a block entity type, or null when unregistered. */
    public String typeId(BlockEntityType<?> type) {
        if (type == null) {
            return null;
        }
        Object key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        return key == null ? null : key.toString();
    }

    public StreamCodec<ByteBuf, Map<?, long[]>> heightmapsCodec() {
        return heightmapsCodec;
    }

    public StreamCodec<RegistryFriendlyByteBuf, List<?>> blockEntityListCodec() {
        return blockEntityListCodec;
    }

    public StreamCodec<ByteBuf, byte[]> dataLayerCodec() {
        return dataLayerCodec;
    }

    public int moveEntityId(ClientboundMoveEntityPacket p) {
        return (int) get(moveEntityId, p);
    }

    public int rotateHeadEntityId(ClientboundRotateHeadPacket p) {
        return (int) get(rotateHeadEntityId, p);
    }

    public int entityEventEntityId(ClientboundEntityEventPacket p) {
        return (int) get(entityEventEntityId, p);
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + f, e);
        }
    }
}
