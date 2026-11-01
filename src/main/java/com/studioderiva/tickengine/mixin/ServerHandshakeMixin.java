package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.report.ReportWebServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers the host name each client typed to join, so report links point where the player can reach. */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakeMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleIntention", at = @At("HEAD"))
	private void tickengine$rememberHost(ClientIntentionPacket packet, CallbackInfo ci) {
		ReportWebServer.rememberJoinHost(connection, packet.hostName());
	}
}
