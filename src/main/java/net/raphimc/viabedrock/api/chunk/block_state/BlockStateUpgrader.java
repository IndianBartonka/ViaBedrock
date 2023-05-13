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
package net.raphimc.viabedrock.api.chunk.block_state;

import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.CompoundTag;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BedrockBlockState;

import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BlockStateUpgrader {

    private final List<BlockStateUpgradeSchema> schemas = new ArrayList<>();

    public BlockStateUpgrader() {
        this.schemas.add(new ValTagBlockStateUpgradeSchema());

        try {
            final String path = "assets/viabedrock/block_state_upgrade_schema";

            try (FileSystem fileSystem = FileSystems.newFileSystem(BlockStateUpgrader.class.getClassLoader().getResource(path).toURI(), Collections.emptyMap())) {
                final List<Path> files = Files.walk(fileSystem.getPath(path))
                        .filter(f -> !f.toString().equals(path))
                        .sorted(Comparator.comparing(Path::toString))
                        .collect(Collectors.toList());

                for (Path file : files) {
                    final JsonObject json = GsonUtil.getGson().fromJson(new InputStreamReader(fileSystem.provider().newInputStream(file)), JsonObject.class);
                    this.schemas.add(new JsonBlockStateUpgradeSchema(json));
                }
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Failed to load block state upgrade schema", e);
            this.schemas.clear();
        }
    }

    public void upgradeToLatest(final CompoundTag tag) {
        BedrockBlockState.sanitizeName(tag);

        for (BlockStateUpgradeSchema schema : this.schemas) {
            schema.upgrade(tag);
        }
    }

}