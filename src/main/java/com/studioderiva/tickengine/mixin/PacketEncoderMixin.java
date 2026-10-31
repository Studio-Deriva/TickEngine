package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.network.NetworkProfiler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Network profiler: the size of every packet the server sends, right after it is written. */
@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin {
	@Inject(
		method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;write(Lnet/minecraft/network/FriendlyByteBuf;)V",
			shift = At.Shift.AFTER)
	)
	private void tickengine$measure(ChannelHandlerContext context, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.onEncoded(context, packet, out.readableBytes());
		}
	}
}
