package wtf.esuh.ogguard.visibility.nms;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enough cached spawn state to re-create an entity on a client after it was hidden: the original spawn packet,
 * merged metadata (latest value per data id), latest equipment per slot, recent attribute packets and the latest
 * passenger list. Owned by one channel; only touched on its event loop.
 */
public final class EntityReplay {

    private static final int MAX_ATTRIBUTE_PACKETS = 8;

    private final ClientboundAddEntityPacket spawn;
    private final Map<Integer, SynchedEntityData.DataValue<?>> metadata = new LinkedHashMap<>();
    private final Map<EquipmentSlot, Pair<EquipmentSlot, ItemStack>> equipment = new EnumMap<>(EquipmentSlot.class);
    private final ArrayDeque<ClientboundUpdateAttributesPacket> attributes = new ArrayDeque<>();
    private ClientboundSetPassengersPacket passengers;

    public EntityReplay(ClientboundAddEntityPacket spawn) {
        this.spawn = spawn;
    }

    public void onMetadata(ClientboundSetEntityDataPacket packet) {
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            metadata.put(value.id(), value);
        }
    }

    public void onEquipment(ClientboundSetEquipmentPacket packet) {
        for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
            equipment.put(slot.getFirst(), slot);
        }
    }

    public void onAttributes(ClientboundUpdateAttributesPacket packet) {
        if (attributes.size() >= MAX_ATTRIBUTE_PACKETS) {
            attributes.removeFirst();
        }
        attributes.addLast(packet);
    }

    public void onPassengers(ClientboundSetPassengersPacket packet) {
        passengers = packet;
    }

    /** Packets that re-create the entity at its current tracked position. */
    public List<Packet<? super ClientGamePacketListener>> replay(double x, double y, double z) {
        int id = spawn.getId();
        List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
        out.add(spawn);
        if (!metadata.isEmpty()) {
            out.add(new ClientboundSetEntityDataPacket(id, new ArrayList<>(metadata.values())));
        }
        if (!equipment.isEmpty()) {
            out.add(new ClientboundSetEquipmentPacket(id, new ArrayList<>(equipment.values())));
        }
        out.addAll(attributes);
        out.add(new ClientboundEntityPositionSyncPacket(id,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, spawn.getYRot(), spawn.getXRot()), false));
        if (passengers != null) {
            out.add(passengers);
        }
        return out;
    }
}
