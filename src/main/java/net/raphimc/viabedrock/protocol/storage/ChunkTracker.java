/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2024 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Position;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntityImpl;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.minecraft.metadata.ChunkPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.chunk.ChunkType1_20_2;
import com.viaversion.viaversion.libs.fastutil.ints.*;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.CompoundTag;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.ListTag;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.LongArrayTag;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.NumberTag;
import com.viaversion.viaversion.protocols.protocol1_20_3to1_20_2.packet.ClientboundPackets1_20_3;
import com.viaversion.viaversion.util.CompactArrayUtil;
import com.viaversion.viaversion.util.MathUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockChunk;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockBlockArray;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSectionImpl;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.DimensionIdRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

// TODO: Feature: Block connections
// TODO: Feature: Lighting
public class ChunkTracker extends StoredObject {

    private static final byte[] FULL_LIGHT = new byte[2048];

    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
    }

    private final int dimensionId;
    private final int minY;
    private final int worldHeight;
    private final Type<Chunk> chunkType;

    private final Object chunkLock = new Object();
    private final Map<Long, BedrockChunk> chunks = new HashMap<>();
    private final Set<Long> dirtyChunks = new HashSet<>();

    private final Object subChunkLock = new Object();
    private final Set<SubChunkPosition> subChunkRequests = new HashSet<>();
    private final Set<SubChunkPosition> pendingSubChunks = new HashSet<>();

    private int centerX = 0;
    private int centerZ = 0;
    private int radius;

    public ChunkTracker(final UserConnection user, final int dimensionId) {
        super(user);
        this.dimensionId = dimensionId;

        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final CompoundTag registries = gameSession.getJavaRegistries();
        final String dimensionKey = DimensionIdRewriter.dimensionIdToDimensionKey(this.dimensionId);
        final CompoundTag dimensionRegistry = registries.get("minecraft:dimension_type");
        final ListTag dimensions = dimensionRegistry.get("value");
        final CompoundTag biomeRegistry = registries.get("minecraft:worldgen/biome");
        final ListTag biomes = biomeRegistry.get("value");

        final IntIntPair pair = dimensions.getValue()
                .stream()
                .map(CompoundTag.class::cast)
                .filter(t -> t.get("name").getValue().toString().equals(dimensionKey))
                .findFirst()
                .map(t -> (CompoundTag) t.get("element"))
                .map(t -> new IntIntImmutablePair(((NumberTag) t.get("min_y")).asInt(), ((NumberTag) t.get("height")).asInt()))
                .orElse(null);
        if (pair == null) {
            throw new IllegalStateException("Could not find dimension min_y/height for dimension " + dimensionKey);
        }
        this.minY = pair.keyInt();
        this.worldHeight = pair.valueInt();

        this.chunkType = new ChunkType1_20_2(this.worldHeight >> 4, MathUtil.ceilLog2(BedrockProtocol.MAPPINGS.getJavaBlockStates().size()), MathUtil.ceilLog2(biomes.size()));

        final ChunkTracker oldChunkTracker = user.get(ChunkTracker.class);
        this.radius = oldChunkTracker != null ? oldChunkTracker.radius : user.get(ClientSettingsStorage.class).getViewDistance();
    }

    public void setCenter(final int x, final int z) throws Exception {
        this.centerX = x;
        this.centerZ = z;
        this.removeOutOfLoadDistanceChunks();
    }

    public void setRadius(final int radius) throws Exception {
        this.radius = radius;
        this.removeOutOfLoadDistanceChunks();
    }

    public BedrockChunk createChunk(final int chunkX, final int chunkZ, final int nonNullSectionCount) throws Exception {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return null;
        if (!this.isInRenderDistance(chunkX, chunkZ)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received chunk outside of render distance, but within load distance: " + chunkX + ", " + chunkZ);
            final EntityTracker entityTracker = this.getUser().get(EntityTracker.class);
            final PacketWrapper updateViewPosition = PacketWrapper.create(ClientboundPackets1_20_3.UPDATE_VIEW_POSITION, this.getUser());
            updateViewPosition.write(Type.VAR_INT, (int) entityTracker.getClientPlayer().position().x() >> 4); // chunk x
            updateViewPosition.write(Type.VAR_INT, (int) entityTracker.getClientPlayer().position().z() >> 4); // chunk z
            updateViewPosition.send(BedrockProtocol.class);
        }

        final BedrockChunk chunk = new BedrockChunk(chunkX, chunkZ, new BedrockChunkSection[this.worldHeight >> 4]);
        for (int i = 0; i < nonNullSectionCount && i < chunk.getSections().length; i++) {
            chunk.getSections()[i] = new BedrockChunkSectionImpl();
        }
        for (int i = 0; i < chunk.getSections().length; i++) {
            if (chunk.getSections()[i] == null) {
                chunk.getSections()[i] = new BedrockChunkSectionImpl(true);
            }
        }
        synchronized (this.chunkLock) {
            this.chunks.put(ChunkPosition.chunkKey(chunk.getX(), chunk.getZ()), chunk);
        }
        return chunk;
    }

    public void unloadChunk(final ChunkPosition chunkPos) throws Exception {
        synchronized (this.chunkLock) {
            this.chunks.remove(chunkPos.chunkKey());
        }
        this.getUser().get(EntityTracker.class).removeItemFrame(chunkPos);

        final PacketWrapper unloadChunk = PacketWrapper.create(ClientboundPackets1_20_3.UNLOAD_CHUNK, this.getUser());
        unloadChunk.write(Type.CHUNK_POSITION, chunkPos); // chunk position
        unloadChunk.send(BedrockProtocol.class);
    }

    public BedrockChunk getChunk(final int chunkX, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return null;

        synchronized (this.chunkLock) {
            return this.chunks.get(ChunkPosition.chunkKey(chunkX, chunkZ));
        }
    }

    public BedrockChunkSection getChunkSection(final int chunkX, final int subChunkY, final int chunkZ) {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) return null;

        final int sectionIndex = subChunkY + Math.abs(this.minY >> 4);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return null;

        return chunk.getSections()[sectionIndex];
    }

    public BedrockChunkSection getChunkSection(final Position blockPosition) {
        return this.getChunkSection(blockPosition.x() >> 4, blockPosition.y() >> 4, blockPosition.z() >> 4);
    }

    public int getBlockState(final Position blockPosition) {
        return this.getBlockState(0, blockPosition);
    }

    public int getBlockState(final int layer, final Position blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return this.airId();
        if (chunkSection.palettesCount(PaletteType.BLOCKS) <= layer) return this.airId();

        return chunkSection.palettes(PaletteType.BLOCKS).get(layer).idAt(blockPosition.x() & 15, blockPosition.y() & 15, blockPosition.z() & 15);
    }

    public int getJavaBlockState(final Position blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return 0;

        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        return this.getJavaBlockState(chunkSection, sectionX, sectionY, sectionZ);
    }

    public int getJavaBlockState(final BedrockChunkSection section, final int sectionX, final int sectionY, final int sectionZ) {
        final BlockStateRewriter blockStateRewriter = this.getUser().get(BlockStateRewriter.class);
        final List<DataPalette> blockPalettes = section.palettes(PaletteType.BLOCKS);

        final int layer0BlockState = blockPalettes.get(0).idAt(sectionX, sectionY, sectionZ);
        int remappedBlockState = blockStateRewriter.javaId(layer0BlockState);
        if (remappedBlockState == -1) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + layer0BlockState);
            remappedBlockState = 0;
        }

        if (blockPalettes.size() > 1) {
            final int layer1BlockState = blockPalettes.get(1).idAt(sectionX, sectionY, sectionZ);
            if (BlockStateRewriter.TAG_WATER.equals(blockStateRewriter.tag(layer1BlockState))) { // Waterlogging
                final int prevBlockState = remappedBlockState;
                remappedBlockState = blockStateRewriter.waterlog(remappedBlockState);
                if (remappedBlockState == -1) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + prevBlockState);
                    remappedBlockState = prevBlockState;
                }
            }
        }

        return remappedBlockState;
    }

    public BedrockBlockEntity getBlockEntity(final Position blockPosition) {
        final BedrockChunk chunk = this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4);
        if (chunk == null) return null;

        return chunk.getBlockEntityAt(blockPosition);
    }

    public void addBlockEntity(final BedrockBlockEntity bedrockBlockEntity) {
        final BedrockChunk chunk = this.getChunk(bedrockBlockEntity.position().x() >> 4, bedrockBlockEntity.position().z() >> 4);
        if (chunk == null) return;

        chunk.removeBlockEntityAt(bedrockBlockEntity.position());
        chunk.blockEntities().add(bedrockBlockEntity);
    }

    public boolean isChunkLoaded(final ChunkPosition chunkPos) {
        if (!this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) return false;

        synchronized (this.chunkLock) {
            return this.chunks.containsKey(chunkPos.chunkKey());
        }
    }

    public boolean isInUnloadedChunkSection(final Position3f playerPosition) {
        final Position chunkSectionPosition = new Position((int) Math.floor(playerPosition.x() / 16), (int) Math.floor((playerPosition.y() - 1.62F) / 16), (int) Math.floor(playerPosition.z() / 16));
        final ChunkPosition chunkPos = new ChunkPosition(chunkSectionPosition.x(), chunkSectionPosition.z());
        if (!this.isChunkLoaded(chunkPos)) {
            return true;
        }
        final BedrockChunkSection chunkSection = this.getChunkSection(chunkSectionPosition.x(), chunkSectionPosition.y(), chunkSectionPosition.z());
        if (chunkSection == null) {
            return false;
        }
        if (chunkSection.hasPendingBlockUpdates()) {
            return true;
        }
        synchronized (this.dirtyChunks) {
            return this.dirtyChunks.contains(chunkPos.chunkKey());
        }
    }

    public boolean isInLoadDistance(final int chunkX, final int chunkZ) {
        if (!this.isInRenderDistance(chunkX, chunkZ)) { // Bedrock accepts chunks outside the chunk render range and uses the player position as a center to determine if a chunk is allowed to be loaded
            final EntityTracker entityTracker = this.getUser().get(EntityTracker.class);
            if (entityTracker == null) return false;
            final int centerX = (int) entityTracker.getClientPlayer().position().x() >> 4;
            final int centerZ = (int) entityTracker.getClientPlayer().position().z() >> 4;
            return Math.abs(chunkX - centerX) <= this.radius && Math.abs(chunkZ - centerZ) <= this.radius;
        }

        return true;
    }

    public boolean isInRenderDistance(final int chunkX, final int chunkZ) {
        return Math.abs(chunkX - this.centerX) <= this.radius && Math.abs(chunkZ - this.centerZ) <= this.radius;
    }

    public void removeOutOfLoadDistanceChunks() throws Exception {
        final Set<ChunkPosition> chunksToRemove = new HashSet<>();
        synchronized (this.chunkLock) {
            for (long chunkKey : this.chunks.keySet()) {
                final ChunkPosition chunkPos = new ChunkPosition(chunkKey);
                if (this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) continue;

                chunksToRemove.add(chunkPos);
            }
        }
        for (ChunkPosition chunkPos : chunksToRemove) {
            this.unloadChunk(chunkPos);
        }
    }

    public void requestSubChunks(final int chunkX, final int chunkZ, final int from, final int to) {
        for (int i = from; i < to; i++) {
            this.requestSubChunk(chunkX, i, chunkZ);
        }
    }

    public void requestSubChunk(final int chunkX, final int subChunkY, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return;

        synchronized (this.subChunkLock) {
            this.subChunkRequests.add(new SubChunkPosition(chunkX, subChunkY, chunkZ));
        }
    }

    public boolean mergeSubChunk(final int chunkX, final int subChunkY, final int chunkZ, final BedrockChunkSection other, final List<BedrockBlockEntity> blockEntities) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return false;

        final SubChunkPosition position = new SubChunkPosition(chunkX, subChunkY, chunkZ);
        synchronized (this.subChunkLock) {
            if (!this.pendingSubChunks.contains(position)) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received sub chunk that was not requested: " + position);
                return false;
            }
            this.pendingSubChunks.remove(position);
        }

        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received sub chunk for unloaded chunk: " + position);
            return false;
        }

        final BedrockChunkSection section = chunk.getSections()[subChunkY + Math.abs(this.minY >> 4)];
        section.mergeWith(this.handleBlockPalette(other));
        section.applyPendingBlockUpdates(this.airId());
        blockEntities.forEach(blockEntity -> chunk.removeBlockEntityAt(blockEntity.position()));
        chunk.blockEntities().addAll(blockEntities);

        return true;
    }

    public IntObjectPair<BlockEntity> handleBlockChange(final Position blockPosition, final int layer, final int blockState) throws Exception {
        final BedrockChunkSection section = this.getChunkSection(blockPosition);
        if (section == null) {
            return null;
        }

        final BlockStateRewriter blockStateRewriter = this.getUser().get(BlockStateRewriter.class);
        final EntityTracker entityTracker = this.getUser().get(EntityTracker.class);
        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        if (section.hasPendingBlockUpdates()) {
            section.addPendingBlockUpdate(sectionX, sectionY, sectionZ, layer, blockState);
            return null;
        }

        while (section.palettesCount(PaletteType.BLOCKS) <= layer) {
            final BedrockDataPalette palette = new BedrockDataPalette();
            palette.addId(this.airId());
            section.addPalette(PaletteType.BLOCKS, palette);
        }
        final DataPalette palette = section.palettes(PaletteType.BLOCKS).get(layer);
        final int prevBlockState = palette.idAt(sectionX, sectionY, sectionZ);
        final String prevTag = blockStateRewriter.tag(prevBlockState);
        final String tag = blockStateRewriter.tag(blockState);
        palette.setIdAt(sectionX, sectionY, sectionZ, blockState);

        int remappedBlockState = this.getJavaBlockState(section, sectionX, sectionY, sectionZ);
        if (!Objects.equals(prevTag, tag)) {
            this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4).removeBlockEntityAt(blockPosition);
            entityTracker.removeItemFrame(blockPosition);
        }

        if (prevBlockState != blockState) {
            if (BlockEntityRewriter.isJavaBlockEntity(tag)) {
                final BedrockBlockEntity bedrockBlockEntity = this.getBlockEntity(blockPosition);
                BlockEntity javaBlockEntity = null;
                if (bedrockBlockEntity != null) {
                    javaBlockEntity = BlockEntityRewriter.toJava(this.getUser(), blockState, bedrockBlockEntity);
                    if (javaBlockEntity instanceof BlockEntityWithBlockState) {
                        final BlockEntityWithBlockState blockEntityWithBlockState = (BlockEntityWithBlockState) javaBlockEntity;
                        if (blockEntityWithBlockState.hasBlockState()) {
                            remappedBlockState = blockEntityWithBlockState.blockState();
                        }
                    }
                } else if (BedrockProtocol.MAPPINGS.getJavaBlockEntities().containsKey(tag)) {
                    final int javaType = BedrockProtocol.MAPPINGS.getJavaBlockEntities().get(tag);
                    javaBlockEntity = new BlockEntityImpl(BlockEntity.pack(sectionX, sectionZ), (short) blockPosition.y(), javaType, new CompoundTag());
                }

                if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                    return new IntObjectImmutablePair<>(remappedBlockState, javaBlockEntity);
                }
            } else if (BlockStateRewriter.TAG_ITEM_FRAME.equals(tag)) {
                this.getUser().get(EntityTracker.class).spawnItemFrame(blockPosition, blockStateRewriter.blockState(blockState));
            }
        }

        return new IntObjectImmutablePair<>(remappedBlockState, null);
    }

    public BedrockChunkSection handleBlockPalette(final BedrockChunkSection section) {
        this.replaceLegacyBlocks(section);
        this.resolvePersistentIds(section);
        return section;
    }

    public void sendChunkInNextTick(final int chunkX, final int chunkZ) {
        synchronized (this.dirtyChunks) {
            this.dirtyChunks.add(ChunkPosition.chunkKey(chunkX, chunkZ));
        }
    }

    public void sendChunk(final int chunkX, final int chunkZ) throws Exception {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        final Chunk remappedChunk = this.remapChunk(chunk);

        final PacketWrapper wrapper = PacketWrapper.create(ClientboundPackets1_20_3.CHUNK_DATA, this.getUser());
        final BitSet lightMask = new BitSet();
        lightMask.set(0, remappedChunk.getSections().length + 2);
        wrapper.write(this.chunkType, remappedChunk); // chunk
        wrapper.write(Type.LONG_ARRAY_PRIMITIVE, lightMask.toLongArray()); // sky light mask
        wrapper.write(Type.LONG_ARRAY_PRIMITIVE, new long[0]); // block light mask
        wrapper.write(Type.LONG_ARRAY_PRIMITIVE, new long[0]); // empty sky light mask
        wrapper.write(Type.LONG_ARRAY_PRIMITIVE, lightMask.toLongArray()); // empty block light mask
        wrapper.write(Type.VAR_INT, remappedChunk.getSections().length + 2); // sky light length
        for (int i = 0; i < remappedChunk.getSections().length + 2; i++) {
            wrapper.write(Type.BYTE_ARRAY_PRIMITIVE, FULL_LIGHT); // sky light
        }
        wrapper.write(Type.VAR_INT, 0); // block light length
        wrapper.send(BedrockProtocol.class);
    }

    public int getDimensionId() {
        return this.dimensionId;
    }

    public int getMinY() {
        return this.minY;
    }

    public int getMaxY() {
        return this.worldHeight - Math.abs(this.minY);
    }

    public int getWorldHeight() {
        return this.worldHeight;
    }

    public int airId() {
        return this.getUser().get(BlockStateRewriter.class).bedrockId(BedrockBlockState.AIR);
    }

    public boolean isEmpty() {
        boolean empty = true;
        synchronized (this.chunkLock) {
            empty &= this.chunks.isEmpty();
        }
        synchronized (this.subChunkLock) {
            empty &= this.subChunkRequests.isEmpty() && this.pendingSubChunks.isEmpty();
        }
        return empty;
    }

    public void tick() throws Exception {
        synchronized (this.dirtyChunks) {
            if (!this.dirtyChunks.isEmpty()) {
                this.getUser().getChannel().eventLoop().submit(() -> {
                    if (!this.getUser().getChannel().isActive()) return;

                    synchronized (this.dirtyChunks) {
                        for (Long dirtyChunk : this.dirtyChunks) {
                            final ChunkPosition chunkPos = new ChunkPosition(dirtyChunk);

                            try {
                                this.sendChunk(chunkPos.chunkX(), chunkPos.chunkZ());
                            } catch (Throwable e) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to send chunk " + chunkPos.chunkX() + ", " + chunkPos.chunkZ(), e);
                            }
                        }
                        this.dirtyChunks.clear();
                    }
                });
            }
        }

        if (this.getUser().get(EntityTracker.class) == null || !this.getUser().get(EntityTracker.class).getClientPlayer().isInitiallySpawned()) {
            return;
        }

        synchronized (this.subChunkLock) {
            this.subChunkRequests.removeIf(s -> !this.isInLoadDistance(s.chunkX, s.chunkZ));

            final Position basePosition = new Position(this.centerX, 0, this.centerZ);
            while (!this.subChunkRequests.isEmpty()) {
                final Set<SubChunkPosition> group = this.subChunkRequests.stream().limit(256).collect(Collectors.toSet());
                this.subChunkRequests.removeAll(group);

                final PacketWrapper subChunkRequest = PacketWrapper.create(ServerboundBedrockPackets.SUB_CHUNK_REQUEST, this.getUser());
                subChunkRequest.write(BedrockTypes.VAR_INT, this.dimensionId); // dimension id
                subChunkRequest.write(BedrockTypes.POSITION_3I, basePosition); // base position
                subChunkRequest.write(BedrockTypes.INT_LE, group.size()); // sub chunk offset count
                for (SubChunkPosition subChunkPosition : group) {
                    this.pendingSubChunks.add(subChunkPosition);
                    final Position offset = new Position(subChunkPosition.chunkX - basePosition.x(), subChunkPosition.subChunkY, subChunkPosition.chunkZ - basePosition.z());
                    subChunkRequest.write(BedrockTypes.SUB_CHUNK_OFFSET, offset); // offset
                }
                subChunkRequest.sendToServer(BedrockProtocol.class);
            }
        }
    }

    private Chunk remapChunk(final BedrockChunk chunk) throws Exception {
        final BlockStateRewriter blockStateRewriter = this.getUser().get(BlockStateRewriter.class);
        final int airId = this.airId();

        final Chunk remappedChunk = new Chunk1_18(chunk.getX(), chunk.getZ(), new ChunkSection[chunk.getSections().length], new CompoundTag(), new ArrayList<>());

        final BedrockChunkSection[] bedrockSections = chunk.getSections();
        final ChunkSection[] remappedSections = remappedChunk.getSections();
        for (int idx = 0; idx < bedrockSections.length; idx++) {
            final BedrockChunkSection bedrockSection = bedrockSections[idx];
            final List<DataPalette> blockPalettes = bedrockSection.palettes(PaletteType.BLOCKS);
            final ChunkSection remappedSection = remappedSections[idx] = new ChunkSectionImpl(false);
            final DataPalette remappedBlockPalette = remappedSection.palette(PaletteType.BLOCKS);

            if (!blockPalettes.isEmpty()) {
                final DataPalette layer0 = blockPalettes.get(0);
                if (layer0.size() == 1) {
                    remappedBlockPalette.addId(layer0.idByIndex(0));
                } else {
                    this.transferPaletteData(layer0, remappedBlockPalette);
                }

                final String[] paletteIndexBlockStateTags = new String[remappedBlockPalette.size()];
                for (int i = 0; i < remappedBlockPalette.size(); i++) {
                    final int bedrockBlockState = remappedBlockPalette.idByIndex(i);
                    int javaBlockState = blockStateRewriter.javaId(bedrockBlockState);
                    if (javaBlockState == -1) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + bedrockBlockState);
                        javaBlockState = 0;
                    }
                    remappedBlockPalette.setIdByIndex(i, javaBlockState);

                    paletteIndexBlockStateTags[i] = blockStateRewriter.tag(bedrockBlockState);
                }

                int nonAirBlockCount = 0;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            final int paletteIndex = remappedBlockPalette.paletteIndexAt(remappedBlockPalette.index(x, y, z));
                            final int javaBlockState = remappedBlockPalette.idByIndex(paletteIndex);
                            if (javaBlockState != 0) {
                                nonAirBlockCount++;
                            }

                            final String tag = paletteIndexBlockStateTags[paletteIndex];
                            if (tag == null) continue;

                            final int absY = this.minY + idx * 16 + y;
                            final Position position = new Position(chunk.getX() * 16 + x, absY, chunk.getZ() * 16 + z);
                            if (BlockEntityRewriter.isJavaBlockEntity(tag)) {
                                final BedrockBlockEntity bedrockBlockEntity = chunk.getBlockEntityAt(position);
                                if (bedrockBlockEntity != null) {
                                    final BlockEntity javaBlockEntity = BlockEntityRewriter.toJava(this.getUser(), layer0.idAt(x, y, z), bedrockBlockEntity);
                                    if (javaBlockEntity instanceof BlockEntityWithBlockState) {
                                        final BlockEntityWithBlockState blockEntityWithBlockState = (BlockEntityWithBlockState) javaBlockEntity;
                                        if (blockEntityWithBlockState.hasBlockState()) {
                                            remappedBlockPalette.setIdAt(x, y, z, blockEntityWithBlockState.blockState());
                                        }
                                    }
                                    if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                                        remappedChunk.blockEntities().add(javaBlockEntity);
                                    }
                                } else if (BedrockProtocol.MAPPINGS.getJavaBlockEntities().containsKey(tag)) {
                                    final int javaType = BedrockProtocol.MAPPINGS.getJavaBlockEntities().get(tag);
                                    final BlockEntity javaBlockEntity = new BlockEntityImpl(BlockEntity.pack(x, z), (short) absY, javaType, new CompoundTag());
                                    remappedChunk.blockEntities().add(javaBlockEntity);
                                }
                            } else if (BlockStateRewriter.TAG_ITEM_FRAME.equals(tag)) {
                                this.getUser().get(EntityTracker.class).spawnItemFrame(position, blockStateRewriter.blockState(layer0.idAt(x, y, z)));
                            }
                        }
                    }
                }
                remappedSection.setNonAirBlocksCount(nonAirBlockCount);

                if (blockPalettes.size() > 1) {
                    final DataPalette layer1 = blockPalettes.get(1);
                    if (layer1.size() != 1 || layer1.idByIndex(0) != airId) {
                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    final int prevBlockState = layer0.idAt(x, y, z);
                                    if (prevBlockState == airId) continue;
                                    final int blockState = layer1.idAt(x, y, z);
                                    if (blockState == airId) continue;
                                    final int javaBlockState = remappedBlockPalette.idAt(x, y, z);

                                    if (BlockStateRewriter.TAG_WATER.equals(blockStateRewriter.tag(blockState))) { // Waterlogging
                                        final int remappedBlockState = blockStateRewriter.waterlog(javaBlockState);
                                        if (remappedBlockState == -1) {
                                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + prevBlockState);
                                        } else {
                                            remappedBlockPalette.setIdAt(x, y, z, remappedBlockState);
                                        }
                                    } else {
                                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Invalid layer 2 block state. L1: " + prevBlockState + ", L2: " + blockState);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                remappedBlockPalette.addId(0);
            }

            final DataPalette biomePalette = bedrockSection.palette(PaletteType.BIOMES);
            final DataPalette remappedBiomePalette = new DataPaletteImpl(ChunkSection.BIOME_SIZE);
            remappedSection.addPalette(PaletteType.BIOMES, remappedBiomePalette);

            if (biomePalette != null) {
                if (biomePalette.size() == 1) {
                    remappedBiomePalette.addId(biomePalette.idByIndex(0));
                } else {
                    for (int x = 0; x < 4; x++) {
                        for (int y = 0; y < 4; y++) {
                            for (int z = 0; z < 4; z++) {
                                final Int2IntMap subBiomes = new Int2IntOpenHashMap();
                                int maxBiomeId = -1;
                                int maxValue = -1;
                                for (int subX = 0; subX < 4; subX++) {
                                    for (int subY = 0; subY < 4; subY++) {
                                        for (int subZ = 0; subZ < 4; subZ++) {
                                            final int biomeId = biomePalette.idAt(x * 4 + subX, y * 4 + subY, z * 4 + subZ);
                                            final int value = subBiomes.getOrDefault(biomeId, 0) + 1;
                                            subBiomes.put(biomeId, value);
                                            if (value > maxValue) {
                                                maxBiomeId = biomeId;
                                                maxValue = value;
                                            }
                                        }
                                    }
                                }
                                remappedBiomePalette.setIdAt(x, y, z, maxBiomeId);
                            }
                        }
                    }
                }

                for (int i = 0; i < remappedBiomePalette.size(); i++) {
                    final int bedrockBiome = remappedBiomePalette.idByIndex(i);
                    int javaBiome = bedrockBiome + 1;
                    if (!BedrockProtocol.MAPPINGS.getBedrockBiomes().inverse().containsKey(bedrockBiome)) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing biome: " + bedrockBiome);
                        javaBiome = 0;
                    }
                    remappedBiomePalette.setIdByIndex(i, javaBiome);
                }
            } else {
                remappedBiomePalette.addId(0);
            }
        }

        final IntSet motionBlockingBlockStates = BedrockProtocol.MAPPINGS.getJavaHeightMapBlockStates().get("motion_blocking");
        final int[] worldSurface = new int[16 * 16];
        final int[] motionBlocking = new int[16 * 16];
        Arrays.fill(worldSurface, Integer.MIN_VALUE);
        Arrays.fill(motionBlocking, Integer.MIN_VALUE);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = z << 4 | x;
                FIND_Y:
                for (int idx = remappedSections.length - 1; idx >= 0; idx--) {
                    final DataPalette blockPalette = remappedSections[idx].palette(PaletteType.BLOCKS);
                    if (blockPalette.size() == 1 && blockPalette.idByIndex(0) == 0) continue;

                    for (int y = 15; y >= 0; y--) {
                        final int blockState = blockPalette.idAt(x, y, z);
                        if (blockState != 0) {
                            final int value = idx * 16 + y + 1;

                            if (worldSurface[index] == Integer.MIN_VALUE) {
                                worldSurface[index] = value;
                            }
                            if (motionBlocking[index] == Integer.MIN_VALUE && motionBlockingBlockStates.contains(blockState)) {
                                motionBlocking[index] = value;
                                break FIND_Y;
                            }
                        }
                    }
                }

                if (worldSurface[index] == Integer.MIN_VALUE) {
                    worldSurface[index] = this.minY;
                }
                if (motionBlocking[index] == Integer.MIN_VALUE) {
                    motionBlocking[index] = this.minY;
                }
            }
        }

        final int bitsPerEntry = MathUtil.ceilLog2(this.worldHeight + 1);
        remappedChunk.getHeightMap().put("WORLD_SURFACE", new LongArrayTag(CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, worldSurface.length, i -> worldSurface[i])));
        remappedChunk.getHeightMap().put("MOTION_BLOCKING", new LongArrayTag(CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, motionBlocking.length, i -> motionBlocking[i])));

        return remappedChunk;
    }

    private void resolvePersistentIds(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.getUser().get(BlockStateRewriter.class);

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockDataPalette) {
                final BedrockDataPalette bedrockPalette = (BedrockDataPalette) palette;
                if (bedrockPalette.usesPersistentIds()) {
                    bedrockPalette.addId(this.airId());
                    bedrockPalette.resolvePersistentIds(tag -> {
                        int remappedBlockState = blockStateRewriter.bedrockId((CompoundTag) tag);
                        if (remappedBlockState == -1) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + tag);
                            remappedBlockState = blockStateRewriter.bedrockId(BedrockBlockState.INFO_UPDATE);
                        }
                        return remappedBlockState;
                    });
                }
            }
        }
    }

    private void replaceLegacyBlocks(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.getUser().get(BlockStateRewriter.class);

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockBlockArray) {
                final BedrockBlockArray blockArray = (BedrockBlockArray) palette;
                final BedrockDataPalette dataPalette = new BedrockDataPalette();
                this.transferPaletteData(blockArray, dataPalette);
                for (int i = 0; i < dataPalette.size(); i++) {
                    final int blockState = dataPalette.idByIndex(i);
                    int remappedBlockState = blockStateRewriter.bedrockId(blockState);
                    if (remappedBlockState == -1) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing legacy block state: " + blockState);
                        remappedBlockState = this.airId();
                    }
                    dataPalette.setIdByIndex(i, remappedBlockState);
                }
                palettes.set(palettes.indexOf(palette), dataPalette);
            }
        }
    }

    /**
     * Transfers the palette data between two different palette types.
     *
     * @param source The source palette
     * @param target The target palette
     */
    private void transferPaletteData(final DataPalette source, final DataPalette target) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    target.setIdAt(x, y, z, source.idAt(x, y, z));
                }
            }
        }
    }

    private static class SubChunkPosition {

        private final int chunkX;
        private final int subChunkY;
        private final int chunkZ;

        private SubChunkPosition(final int chunkX, final int subChunkY, final int chunkZ) {
            this.chunkX = chunkX;
            this.subChunkY = subChunkY;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SubChunkPosition that = (SubChunkPosition) o;
            return chunkX == that.chunkX && subChunkY == that.subChunkY && chunkZ == that.chunkZ;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkX, subChunkY, chunkZ);
        }

        @Override
        public String toString() {
            return "SubChunkPosition{" +
                    "chunkX=" + chunkX +
                    ", subChunkY=" + subChunkY +
                    ", chunkZ=" + chunkZ +
                    '}';
        }

    }

}
