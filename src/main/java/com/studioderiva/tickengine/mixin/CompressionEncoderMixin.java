package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.network.NetworkProfiler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Network profiler: the compressed size of the packet just encoded. The compression handler runs
 * right after the packet encoder, on the same network thread.
 */
@Mixin(CompressionEncoder.class)
abstract class CompressionEncoderMixin {
	@Inject(
		method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
		at = @At("TAIL")
	)
	private void tickengine$measure(ChannelHandlerContext context, ByteBuf in, ByteBuf out, CallbackInfo ci) {
		if (NetworkProfiler.active) {
			NetworkProfiler.onCompressed(out.readableBytes());
		}
	}
}
