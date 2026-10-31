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

/** Network profiler: the size of every packet the server sends, right after it is encoded. */
@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin {
	@Inject(
		method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V",
			shift = At.Shift.AFTER)
	)
	private void tickengine$measure(ChannelHandlerContext context, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.onEncoded(context, packet, out.readableBytes());
		}
	}
}
