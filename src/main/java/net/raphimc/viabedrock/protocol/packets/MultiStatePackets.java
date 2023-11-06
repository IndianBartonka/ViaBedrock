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
package net.raphimc.viabedrock.protocol.packets;

import com.google.common.base.Joiner;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.libs.gson.JsonNull;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.protocol1_20_2to1_20.packet.ClientboundConfigurationPackets1_20_2;
import com.viaversion.viaversion.protocols.protocol1_20_2to1_20.packet.ClientboundPackets1_20_2;
import com.viaversion.viaversion.protocols.protocol1_20_2to1_20.packet.ServerboundConfigurationPackets1_20_2;
import com.viaversion.viaversion.protocols.protocol1_20_2to1_20.packet.ServerboundPackets1_20_2;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.InteractActions;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.MovePlayerModes;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.PlayStatus;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ServerMovementModes;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.task.KeepAliveTask;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public class MultiStatePackets {

    private static final PacketHandler DISCONNECT_HANDLER = wrapper -> {
        wrapper.read(BedrockTypes.VAR_INT); // reason
        final boolean hasMessage = !wrapper.read(Type.BOOLEAN); // skip message
        if (hasMessage) {
            final Map<String, String> translations = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePack().content().getLang("texts/en_US.lang");
            final Function<String, String> translator = k -> translations.getOrDefault(k, k);
            final String rawMessage = wrapper.read(BedrockTypes.STRING); // message
            final String translatedMessage = BedrockTranslator.translate(rawMessage, translator, new Object[0]);
            wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translatedMessage)); // reason
        } else {
            wrapper.write(Type.COMPONENT, JsonNull.INSTANCE); // reason
        }
    };

    private static final PacketHandler PACKET_VIOLATION_WARNING_HANDLER = wrapper -> {
        final int type = wrapper.read(BedrockTypes.VAR_INT) + 1; // type
        final int severity = wrapper.read(BedrockTypes.VAR_INT) + 1; // severity
        final int packetIdCause = wrapper.read(BedrockTypes.VAR_INT) - 1; // cause packet id
        final String context = wrapper.read(BedrockTypes.STRING); // context

        final String[] types = new String[]{"Unknown", "Malformed packet"};
        final String[] severities = new String[]{"Unknown", "Warning", "Final warning", "Terminating connection"};

        final String reason = "§4Packet violation warning: §c"
                + (type >= 0 && type <= types.length ? types[type] : type)
                + " (" + (severity >= 0 && severity <= severities.length ? severities[severity] : severity) + ")\n"
                + "Violating Packet: " + (ServerboundBedrockPackets.getPacket(packetIdCause) != null ? ServerboundBedrockPackets.getPacket(packetIdCause).name() : packetIdCause) + "\n"
                + (context.isEmpty() ? "No context provided" : (" Context: '" + context + "'"))
                + "\n\nPlease report this issue on the ViaBedrock GitHub page!";
        wrapper.write(Type.COMPONENT, TextUtil.stringToGson(reason));
    };

    private static final PacketHandlers KEEP_ALIVE_HANDLER = new PacketHandlers() {
        @Override
        public void register() {
            map(Type.LONG, BedrockTypes.LONG_LE); // id
            create(Type.BOOLEAN, true); // from server
            handler(wrapper -> {
                if (wrapper.get(BedrockTypes.LONG_LE, 0) == KeepAliveTask.INTERNAL_ID) { // It's a keep alive packet sent from ViaBedrock to prevent the client from disconnecting
                    wrapper.cancel();
                }
            });
        }
    };

    public static final PacketHandlers NETWORK_STACK_LATENCY_HANDLER = new PacketHandlers() {
        @Override
        protected void register() {
            map(BedrockTypes.LONG_LE, Type.LONG, t -> t * 1_000_000); // timestamp
            handler(wrapper -> {
                if (!wrapper.read(Type.BOOLEAN)) { // from server
                    wrapper.cancel();
                }
            });
        }
    };

    public static final PacketHandler CLIENT_SETTINGS_HANDLER = wrapper -> {
        final String locale = wrapper.read(Type.STRING); // locale
        final byte viewDistance = wrapper.read(Type.BYTE); // view distance
        final int chatVisibility = wrapper.read(Type.VAR_INT); // chat visibility
        final boolean chatColors = wrapper.read(Type.BOOLEAN); // chat colors
        final short skinParts = wrapper.read(Type.UNSIGNED_BYTE); // skin parts
        final int mainHand = wrapper.read(Type.VAR_INT); // main hand
        final boolean textFiltering = wrapper.read(Type.BOOLEAN); // text filtering
        final boolean serverListing = wrapper.read(Type.BOOLEAN); // server listing
        wrapper.user().put(new ClientSettingsStorage(locale, viewDistance, chatVisibility, chatColors, skinParts, mainHand, textFiltering, serverListing));

        wrapper.write(BedrockTypes.VAR_INT, (int) viewDistance); // radius
        wrapper.write(Type.UNSIGNED_BYTE, ProtocolConstants.BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS); // max radius
    };

    public static final PacketHandler CUSTOM_PAYLOAD_HANDLER = wrapper -> {
        wrapper.cancel();
        final String channel = wrapper.read(Type.STRING); // channel
        if (channel.equals("minecraft:register")) {
            final String[] channels = new String(wrapper.read(Type.REMAINING_BYTES), StandardCharsets.UTF_8).split("\0");
            wrapper.user().get(ChannelStorage.class).addChannels(channels);
        }
    };

    public static final PacketHandler PONG_HANDLER = wrapper -> {
        wrapper.cancel();
        wrapper.user().get(PacketSyncStorage.class).handleResponse(wrapper.read(Type.INT)); // parameter
    };

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.DISCONNECT,
                ClientboundPackets1_20_2.DISCONNECT, DISCONNECT_HANDLER,
                ClientboundLoginPackets.LOGIN_DISCONNECT, DISCONNECT_HANDLER,
                ClientboundConfigurationPackets1_20_2.DISCONNECT, DISCONNECT_HANDLER
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.PACKET_VIOLATION_WARNING,
                ClientboundPackets1_20_2.DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER,
                ClientboundLoginPackets.LOGIN_DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER,
                ClientboundConfigurationPackets1_20_2.DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.PLAY_STATUS,
                State.LOGIN, new PacketHandlers() {
                    @Override
                    public void register() {
                        handler(wrapper -> {
                            final int status = wrapper.read(Type.INT); // status

                            if (status == PlayStatus.LOGIN_SUCCESS) {
                                wrapper.setPacketType(ClientboundLoginPackets.GAME_PROFILE);
                                final AuthChainData authChainData = wrapper.user().get(AuthChainData.class);
                                wrapper.write(Type.UUID, authChainData.getIdentity()); // uuid
                                wrapper.write(Type.STRING, authChainData.getDisplayName()); // username
                                wrapper.write(Type.VAR_INT, 0); // properties length

                                final ProtocolInfo info = wrapper.user().getProtocolInfo();
                                info.setUsername(authChainData.getDisplayName());
                                info.setUuid(authChainData.getIdentity());

                                // Parts of BaseProtocol1_7 GAME_PROFILE handler
                                Via.getManager().getConnectionManager().onLoginSuccess(wrapper.user());
                                if (!info.getPipeline().hasNonBaseProtocols()) {
                                    wrapper.user().setActive(false);
                                }
                                if (Via.getManager().isDebug()) {
                                    ViaBedrock.getPlatform().getLogger().log(Level.INFO, "{0} logged in with protocol {1}, Route: {2}", new Object[]{info.getUsername(), info.getProtocolVersion(), Joiner.on(", ").join(info.getPipeline().pipes(), ", ")});
                                }

                                sendClientCacheStatus(wrapper.user());
                            } else {
                                wrapper.setPacketType(ClientboundLoginPackets.LOGIN_DISCONNECT);
                                writePlayStatusKickMessage(wrapper, status);
                            }
                        });
                    }
                }, State.PLAY, new PacketHandlers() {
                    @Override
                    protected void register() {
                        handler(wrapper -> {
                            final int status = wrapper.read(Type.INT); // status

                            if (status == PlayStatus.LOGIN_SUCCESS) {
                                wrapper.cancel();
                                sendClientCacheStatus(wrapper.user());
                            } else if (status == PlayStatus.PLAYER_SPAWN) { // Spawn player
                                wrapper.cancel();
                                final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
                                final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

                                final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
                                if (clientPlayer.isInitiallySpawned()) {
                                    if (clientPlayer.isChangingDimension()) {
                                        clientPlayer.closeDownloadingTerrainScreen();
                                    }

                                    return;
                                }
                                if (gameSession.getBedrockBiomeDefinitions() == null) {
                                    BedrockProtocol.kickForIllegalState(wrapper.user(), "Tried to spawn the client player before the biome definitions were loaded!");
                                    return;
                                }

                                final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());
                                interact.write(Type.UNSIGNED_BYTE, InteractActions.MOUSEOVER); // action
                                interact.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // runtime entity id
                                interact.write(BedrockTypes.POSITION_3F, new Position3f(0F, 0F, 0F)); // mouse position
                                interact.sendToServer(BedrockProtocol.class);

                                // TODO: Mob Equipment with current held item

                                final PacketWrapper emoteList = PacketWrapper.create(ServerboundBedrockPackets.EMOTE_LIST, wrapper.user());
                                emoteList.write(BedrockTypes.VAR_LONG, clientPlayer.runtimeId()); // runtime entity id
                                emoteList.write(BedrockTypes.UUID_ARRAY, new UUID[0]); // emote ids
                                emoteList.sendToServer(BedrockProtocol.class);

                                clientPlayer.setRotation(new Position3f(clientPlayer.rotation().x(), clientPlayer.rotation().y(), clientPlayer.rotation().y()));
                                clientPlayer.setInitiallySpawned();
                                if (gameSession.getMovementMode() == ServerMovementModes.CLIENT) {
                                    clientPlayer.sendMovePlayerPacketToServer(MovePlayerModes.NORMAL);
                                }

                                final PacketWrapper setLocalPlayerAsInitialized = PacketWrapper.create(ServerboundBedrockPackets.SET_LOCAL_PLAYER_AS_INITIALIZED, wrapper.user());
                                setLocalPlayerAsInitialized.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // runtime entity id
                                setLocalPlayerAsInitialized.sendToServer(BedrockProtocol.class);

                                clientPlayer.closeDownloadingTerrainScreen();
                            } else {
                                wrapper.setPacketType(ClientboundPackets1_20_2.DISCONNECT);
                                writePlayStatusKickMessage(wrapper, status);
                            }
                        });
                    }
                }
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.NETWORK_STACK_LATENCY,
                ClientboundPackets1_20_2.KEEP_ALIVE, NETWORK_STACK_LATENCY_HANDLER,
                ClientboundConfigurationPackets1_20_2.KEEP_ALIVE, NETWORK_STACK_LATENCY_HANDLER
        );

        protocol.registerServerbound(ServerboundPackets1_20_2.KEEP_ALIVE, ServerboundBedrockPackets.NETWORK_STACK_LATENCY, KEEP_ALIVE_HANDLER);
        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_20_2.KEEP_ALIVE, ServerboundBedrockPackets.NETWORK_STACK_LATENCY, KEEP_ALIVE_HANDLER);
    }

    private static void sendClientCacheStatus(final UserConnection user) throws Exception {
        final PacketWrapper clientCacheStatus = PacketWrapper.create(ServerboundBedrockPackets.CLIENT_CACHE_STATUS, user);
        clientCacheStatus.write(Type.BOOLEAN, ViaBedrock.getConfig().isBlobCacheEnabled()); // is supported
        clientCacheStatus.sendToServer(BedrockProtocol.class);
    }

    private static void writePlayStatusKickMessage(final PacketWrapper wrapper, final int status) {
        final Map<String, String> translations = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePack().content().getLang("texts/en_US.lang");

        switch (status) {
            case PlayStatus.LOGIN_FAILED_CLIENT_OLD:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.outdatedClient")));
                break;
            case PlayStatus.LOGIN_FAILED_SERVER_OLD:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.outdatedServer")));
                break;
            case PlayStatus.LOGIN_FAILED_INVALID_TENANT:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.invalidTenant")));
                break;
            case PlayStatus.LOGIN_FAILED_EDITION_MISMATCH_EDU_TO_VANILLA:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.editionMismatchEduToVanilla")));
                break;
            case PlayStatus.LOGIN_FAILED_EDITION_MISMATCH_VANILLA_TO_EDU:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.editionMismatchVanillaToEdu")));
                break;
            case PlayStatus.FAILED_SERVER_FULL_SUB_CLIENT:
            case PlayStatus.VANILLA_TO_EDITOR_MISMATCH:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.serverFull") + "\n\n\n\n" + translations.get("disconnectionScreen.serverFull.title")));
                break;
            case PlayStatus.EDITOR_TO_VANILLA_MISMATCH:
                wrapper.write(Type.COMPONENT, TextUtil.stringToGson(translations.get("disconnectionScreen.editor.mismatchEditorToVanilla")));
                break;
            default: // Mojang client silently ignores invalid values
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received invalid login status: " + status);
            case PlayStatus.PLAYER_SPAWN:
            case PlayStatus.LOGIN_SUCCESS:
                wrapper.cancel();
                break;
        }
    }

}