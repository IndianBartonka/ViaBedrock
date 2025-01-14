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
package net.raphimc.viabedrock.protocol.providers;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.Provider;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import net.lenni0451.mcstructs_bedrock.forms.AForm;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public abstract class FormProvider implements Provider {

    private static final byte USER_CLOSED = 0;
    private static final byte USER_BUSY = 1;

    public abstract void openModalForm(final UserConnection user, final int id, final AForm form) throws Exception;

    public void sendModalFormResponse(final UserConnection user, final int id, final AForm form) throws Exception {
        final PacketWrapper modalFormResponse = PacketWrapper.create(ClientboundBedrockPackets.MODAL_FORM_RESPONSE, user);
        modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, id); // id
        modalFormResponse.write(Type.BOOLEAN, form != null); // has response
        if (form != null) {
            modalFormResponse.write(BedrockTypes.STRING, form.serializeResponse() + "\n"); // response
            modalFormResponse.write(Type.BOOLEAN, false); // has cancel reason
        } else {
            modalFormResponse.write(Type.BOOLEAN, true); // has cancel reason
            modalFormResponse.write(Type.BYTE, this.isAnyScreenOpen(user) ? USER_BUSY : USER_CLOSED); // cancel reason
        }
        modalFormResponse.sendToServer(BedrockProtocol.class);
    }

    public boolean isAnyScreenOpen(final UserConnection user) {
        return user.get(InventoryTracker.class).isAnyContainerOpen();
    }

}
