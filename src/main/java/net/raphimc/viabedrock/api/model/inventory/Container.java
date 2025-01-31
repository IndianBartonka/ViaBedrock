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
package net.raphimc.viabedrock.api.model.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.data.enums.MenuType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

public abstract class Container {

    protected final byte windowId;
    protected final MenuType menuType;
    protected final BedrockItem[] items;

    public Container(final byte windowId, final MenuType menuType, final int size) {
        this.windowId = windowId;
        this.menuType = menuType;
        this.items = new BedrockItem[size];
    }

    public void setItems(final BedrockItem[] items) {
        if (this.items.length != items.length) throw new IllegalArgumentException("Items length must be equal to container size");

        System.arraycopy(items, 0, this.items, 0, items.length);
    }

    public boolean handleWindowClick(final int revision, final short slot, final byte button, final int action) throws Exception {
        return false;
    }

    public Item[] getJavaItems(final UserConnection user) {
        return user.get(ItemRewriter.class).javaItems(this.items);
    }

    public byte windowId() {
        return this.windowId;
    }

    public MenuType menuType() {
        return this.menuType;
    }

    public BedrockItem[] items() {
        return this.items;
    }

}
