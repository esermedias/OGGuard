package wtf.esuh.ogguard.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import wtf.esuh.ogguard.visibility.nms.NmsBridge;
import wtf.esuh.ogguard.visibility.nms.PacketMasker;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityPolicy;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side verification of the chunk rewrite against real server classes, without a client. Builds a real
 * chunk packet from a loaded chunk (exactly like PlayerChunkSender), masks it for a concealed viewer, decodes the
 * result with the real section codec and compares every block with the live chunk. Read-only: the world is never
 * modified. Must run on the region thread owning the chunk.
 */
final class MaskingSelfTest {

    private MaskingSelfTest() {
    }

    @SuppressWarnings("deprecation")
    static List<String> run(NmsBridge nms, VisibilityPolicy policy, World world, int cx, int cz) {
        List<String> report = new ArrayList<>();
        ServerLevel level = ((CraftWorld) world).getHandle();
        world.getChunkAt(cx, cz); // synchronous load is allowed on the owning region thread
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            report.add("FAIL chunk " + cx + "," + cz + " not loaded");
            return report;
        }
        String name = world.getName();
        PacketMasker masker = new PacketMasker(nms);
        BlockState replacement = nms.replacementState(policy.config().replacement());
        ClientboundLevelChunkWithLightPacket real =
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, false);

        // 1) concealed viewer far above, other chunk
        ViewerState concealed = new ViewerState(name, (cx << 4) + 8, 200, (cz << 4) + 8, false, false);
        ClientboundLevelChunkWithLightPacket masked = masker.maskChunk(real, policy, concealed, level, name);
        int minSection = level.getMinSectionY();
        FriendlyByteBuf in = masked.getChunkData().getReadBuffer();
        long checked = 0;
        long hiddenOk = 0;
        long mismatches = 0;
        for (int i = 0; i < level.getSectionsCount(); i++) {
            LevelChunkSection s = new LevelChunkSection(
                    new PalettedContainer<>(Blocks.AIR.defaultBlockState(), nms.containerFactory().blockStatesStrategy()),
                    nms.containerFactory().createForBiomes());
            s.read(in);
            int baseY = (minSection + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int wx = (cx << 4) + x;
                        int wy = baseY + y;
                        int wz = (cz << 4) + z;
                        BlockState sent = s.getBlockState(x, y, z);
                        BlockState live = chunk.getBlockState(new BlockPos(wx, wy, wz));
                        BlockState expected = policy.maskBlock(concealed, name, wx, wy, wz) ? replacement : live;
                        checked++;
                        if (sent != expected) {
                            mismatches++;
                        } else if (expected == replacement && live != replacement) {
                            hiddenOk++;
                        }
                    }
                }
            }
        }
        report.add((mismatches == 0 ? "PASS" : "FAIL") + " sections: " + checked + " blocks compared, "
                + hiddenOk + " real blocks hidden, " + mismatches + " mismatches, trailing bytes=" + in.readableBytes());

        int realBe = nms.blockEntities(real.getChunkData()).size();
        int maskedBe = nms.blockEntities(masked.getChunkData()).size();
        long realBeBelow = chunk.getBlockEntities().keySet().stream()
                .filter(p -> policy.maskBlockEntity(concealed, name, p.getX(), p.getY(), p.getZ())).count();
        report.add((realBe - maskedBe == realBeBelow ? "PASS" : "FAIL") + " block entities: real=" + realBe
                + ", sent=" + maskedBe + ", hidden expected=" + realBeBelow);

        int skyBits = masked.getLightData().getSkyYMask().cardinality();
        int realSkyBits = real.getLightData().getSkyYMask().cardinality();
        report.add("INFO light: sky sections with data real=" + realSkyBits + ", sent=" + skyBits
                + "; block real=" + real.getLightData().getBlockYMask().cardinality()
                + ", sent=" + masked.getLightData().getBlockYMask().cardinality());

        // 2) revealed viewer standing in the chunk: packet must be passed through untouched
        ViewerState revealed = new ViewerState(name, (cx << 4) + 8, -10, (cz << 4) + 8, true, false);
        report.add((masker.maskChunk(real, policy, revealed, level, name) == real ? "PASS" : "FAIL")
                + " revealed viewer receives the original packet");

        // 3) block update below the boundary
        BlockPos below = new BlockPos(cx << 4, policy.config().boundaryY() - 1, cz << 4);
        ClientboundBlockUpdatePacket update = new ClientboundBlockUpdatePacket(below, Blocks.CHEST.defaultBlockState());
        ClientboundBlockUpdatePacket maskedUpdate = masker.maskBlockUpdate(update, policy, concealed, name);
        boolean expectMask = policy.maskBlock(concealed, name, below.getX(), below.getY(), below.getZ());
        report.add(((maskedUpdate.getBlockState() == replacement) == expectMask ? "PASS" : "FAIL")
                + " block update at " + below.toShortString() + " sent as " + maskedUpdate.getBlockState());
        return report;
    }
}
