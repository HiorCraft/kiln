package dev.kiln.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the configuration-phase listener: its connection (the client disconnects itself when consent is denied)
 * and the server entry (address used as key for consent and keybinds; Minecraft's current server is not set yet).
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerAccessor {
	@Accessor("connection")
	Connection kiln$getConnection();

	@Accessor("serverData")
	ServerData kiln$getServerData();
}
