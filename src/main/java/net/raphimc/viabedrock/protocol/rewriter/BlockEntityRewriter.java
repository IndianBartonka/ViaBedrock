/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023 RK_01/RaphiMC and contributors
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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntityImpl;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.*;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.rewriter.blockentity.*;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

public class BlockEntityRewriter {

    private static final Map<String, Rewriter> BLOCK_ENTITY_REWRITERS = new HashMap<>();

    private static final Rewriter NULL_REWRITER = (user, bedrockBlockEntity) -> null;
    private static final Rewriter NOOP_REWRITER = (user, bedrockBlockEntity) -> new BlockEntityImpl(bedrockBlockEntity.packedXZ(), bedrockBlockEntity.y(), -1, new CompoundTag());

    static {
        // TODO: Enhancement: Add missing block entities
        BLOCK_ENTITY_REWRITERS.put("brewing_stand", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("campfire", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("note_block", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("piston", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("moving_block", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("item_frame", NULL_REWRITER);

        BLOCK_ENTITY_REWRITERS.put("banner", new BannerBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("barrel", new LootableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("beacon", new BeaconBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("bed", new BedBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("beehive", new BeehiveBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("bell", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("blast_furnace", new FurnaceBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("brushable_block", new BrushableBlockBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("calibrated_sculk_sensor", new SculkBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("cauldron", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("chest", new LootableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("chiseled_bookshelf", new ChiseledBookshelfBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("command_block", new CommandBlockBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("comparator", new ComparatorBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("conduit", new ConduitBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("daylight_detector", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("decorated_pot", new DecoratedPotBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("dispenser", new LootableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("dropper", new LootableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("enchanting_table", new EnchantingTableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("end_gateway", new EndGatewayBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("end_portal", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("ender_chest", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("flower_pot", new FlowerPotBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("furnace", new FurnaceBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("hanging_sign", new SignBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("hopper", new HopperBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("jigsaw", new JigsawBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("jukebox", new JukeboxBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("lectern", new LecternBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("lodestone", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("mob_spawner", new MobSpawnerBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("nether_reactor", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("sculk_catalyst", NOOP_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("sculk_sensor", new SculkBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("sculk_shrieker", new SculkBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("shulker_box", new LootableBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("sign", new SignBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("skull", new SkullBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("smoker", new FurnaceBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("spore_blossom", NULL_REWRITER);
        BLOCK_ENTITY_REWRITERS.put("structure_block", new StructureBlockBlockEntityRewriter());
        BLOCK_ENTITY_REWRITERS.put("trapped_chest", new LootableBlockEntityRewriter());
    }

    public static BlockEntity toJava(final UserConnection user, final int bedrockBlockStateId, final BedrockBlockEntity bedrockBlockEntity) {
        final BlockStateRewriter blockStateRewriter = user.get(BlockStateRewriter.class);
        if (bedrockBlockStateId == blockStateRewriter.bedrockId(BedrockBlockState.AIR)) {
            return null;
        }

        final String tag = blockStateRewriter.tag(bedrockBlockStateId);
        if (BLOCK_ENTITY_REWRITERS.containsKey(tag)) {
            final BlockEntity javaBlockEntity = BLOCK_ENTITY_REWRITERS.get(tag).toJava(user, bedrockBlockEntity);
            if (javaBlockEntity == null) return null;

            if (javaBlockEntity.tag() != null) {
                final int typeId = BedrockProtocol.MAPPINGS.getJavaBlockEntities().getOrDefault(tag, -1);
                if (typeId == -1) throw new IllegalStateException("Unknown block entity type: " + tag);

                return javaBlockEntity.withTypeId(typeId);
            }

            return javaBlockEntity;
        } else {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block entity translation for " + bedrockBlockEntity.tag());
        }

        return null;
    }

    public interface Rewriter {

        BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity);

        default void copy(final CompoundTag oldTag, final CompoundTag newTag, final String key, final Class<?> expectedType) {
            this.copy(oldTag, newTag, key, key, expectedType);
        }

        default void copy(final CompoundTag oldTag, final CompoundTag newTag, final String oldKey, final String newKey, final Class<?> expectedType) {
            if (expectedType.isInstance(oldTag.get(oldKey))) {
                newTag.put(newKey, oldTag.get(oldKey));
            }
        }

        default void copyCustomName(final UserConnection user, final CompoundTag oldTag, final CompoundTag newTag) {
            if (oldTag.get("CustomName") instanceof StringTag) {
                newTag.put("CustomName", this.rewriteCustomName(user, oldTag.get("CustomName")));
            }
        }

        default void copyItemList(final UserConnection user, final CompoundTag oldTag, final CompoundTag newTag) {
            if (oldTag.get("Items") instanceof ListTag) {
                newTag.put("Items", this.rewriteItemList(user, oldTag.get("Items")));
            }
        }

        default CompoundTag rewriteItem(final UserConnection user, final CompoundTag bedrockItemTag) {
            return user.get(ItemRewriter.class).javaTag(bedrockItemTag);
        }

        default ListTag rewriteItemList(final UserConnection user, final ListTag bedrockItemList) {
            final ListTag javaItemList = new ListTag(bedrockItemList.getElementType());
            if (CompoundTag.class.equals(bedrockItemList.getElementType())) {
                final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
                for (final Tag bedrockItemTag : bedrockItemList) {
                    final CompoundTag javaItemTag = itemRewriter.javaTag((CompoundTag) bedrockItemTag);
                    this.copy((CompoundTag) bedrockItemTag, javaItemTag, "Slot", ByteTag.class);
                    javaItemList.add(javaItemTag);
                }
            }
            return javaItemList;
        }

        default StringTag rewriteCustomName(final UserConnection user, final StringTag textTag) {
            final Function<String, String> translator = k -> user.get(ResourcePacksStorage.class).getTranslations().getOrDefault(k, k);
            return new StringTag(TextUtil.stringToJson(BedrockTranslator.translate(textTag.getValue(), translator, new Object[0])));
        }

    }

}