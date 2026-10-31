package com.studioderiva.tickengine.network;

import com.google.common.collect.MapMaker;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * On-demand network profiler: measures every packet the server sends while a session runs and
 * charges its size to what it is about (entity type, block, block entity, mod channel, sound,
 * particle), to the mod that registered it and to the chunk it concerns.
 *
 * <p>Sizes are taken where vanilla encodes packets (PacketEncoder) and compresses them
 * (CompressionEncoder), on the network threads. Entity packets do not say which entity type they
 * are for, and looking an entity up from a network thread is not safe, so the server thread tags
 * them when it hands them to the entity tracker ({@link #tagEntityPacket}).
 *
 * <p>When no session runs, the mixins only read {@link #active}.
 */
public final class NetworkProfiler {
	/** True while a session runs. Read by the mixins on every packet. */
	public static volatile boolean active;

	private static volatile NetworkWindow window;
	private static long sessionEndMillis;
	private static Consumer<NetworkSnapshot> sessionListener;
	private static NetworkSnapshot lastSnapshot;

	/** Entity packets tagged on the server thread, read on the network threads. Weak identity keys. */
	private static volatile Map<Packet<?>, NetworkWindow.Share> tagged = newTagMap();

	/** One cached source per registry object (entity type, block, payload type, packet type...) or sound id. */
	private static final ConcurrentHashMap<Object, NetworkSource> SOURCES = new ConcurrentHashMap<>();

	/**
	 * The packet just encoded on this network thread, until the compression handler, which runs right
	 * after it on the same thread, reports the compressed size.
	 */
	private static final ThreadLocal<NetworkWindow.Pending> PENDING = new ThreadLocal<>();

	private NetworkProfiler() {
	}

	private static Map<Packet<?>, NetworkWindow.Share> newTagMap() {
		return new MapMaker().weakKeys().makeMap();
	}

	// --- server thread ---

	/** Starts a session. The listener receives the result on the server thread when it stops. */
	public static boolean startSession(int seconds, Consumer<NetworkSnapshot> listener) {
		if (window != null) {
			return false;
		}
		tagged = newTagMap();
		window = new NetworkWindow();
		sessionEndMillis = System.currentTimeMillis() + seconds * 1000L;
		sessionListener = listener;
		active = true;
		return true;
	}

	public static boolean isSessionRunning() {
		return window != null;
	}

	public static long sessionSecondsLeft() {
		return window == null ? 0 : Math.max(0, (sessionEndMillis - System.currentTimeMillis()) / 1000);
	}

	public static void onTickEnd() {
		if (window != null && System.currentTimeMillis() >= sessionEndMillis) {
			stopSession();
		}
	}

	/**
	 * Stops the running session and hands the result to its listener. Packets still being encoded on
	 * the network threads at this moment may be left out; a session lasts many seconds, so that is noise.
	 */
	public static boolean stopSession() {
		NetworkWindow stopped = window;
		if (stopped == null) {
			return false;
		}
		active = false;
		window = null;
		tagged = newTagMap();
		lastSnapshot = stopped.snapshot("Network session");
		Consumer<NetworkSnapshot> listener = sessionListener;
		sessionListener = null;
		listener.accept(lastSnapshot);
		return true;
	}

	/** Result of the last finished session, or null. */
	public static NetworkSnapshot lastSnapshot() {
		return lastSnapshot;
	}

	/** Drops all data, e.g. when the server stops. */
	public static void reset() {
		active = false;
		window = null;
		tagged = newTagMap();
		sessionListener = null;
		lastSnapshot = null;
	}

	/**
	 * Tags a packet the entity tracker sends about {@code entity}. Server thread, while a session runs.
	 * Bundles are tagged packet by packet, because the network pipeline splits them before encoding.
	 */
	public static void tagEntityPacket(Packet<?> packet, Entity entity) {
		NetworkWindow.Share share = new NetworkWindow.Share(source(entity.getType()), 1,
			new NetworkWindow.ChunkKey(entity.level().dimension(), entity.getBlockX() >> 4, entity.getBlockZ() >> 4),
			new NetworkSnapshot.Spot(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()));
		tag(packet, share, tagged);
	}

	private static void tag(Packet<?> packet, NetworkWindow.Share share, Map<Packet<?>, NetworkWindow.Share> map) {
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> sub : bundle.subPackets()) {
				tag(sub, share, map);
			}
		} else {
			map.put(packet, share);
		}
	}

	/** Wraps the consumer that receives the packets showing an entity to a player, so they get tagged. */
	public static <T extends Packet<?>> Consumer<T> tagging(Consumer<T> consumer, Entity entity) {
		return packet -> {
			tagEntityPacket(packet, entity);
			consumer.accept(packet);
		};
	}

	// --- network threads ---

	/** Called by PacketEncoder after a packet was encoded into {@code rawBytes} bytes. */
	public static void onEncoded(ChannelHandlerContext context, Packet<?> packet, int rawBytes) {
		NetworkWindow current = window;
		if (current == null) {
			return;
		}
		NetworkWindow.Share[] shares;
		long totalWeight = 1;
		NetworkWindow.Share tag = tagged.get(packet);
		if (tag != null) {
			shares = new NetworkWindow.Share[] { tag };
		} else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
			shares = splitByBlock(context, update);
			totalWeight = 0;
			for (NetworkWindow.Share share : shares) {
				totalWeight += share.weight();
			}
		} else {
			shares = new NetworkWindow.Share[] { attribute(context, packet) };
		}
		NetworkWindow.Pending pending = current.recordRaw(packet.getClass(), shares, totalWeight, rawBytes);
		ChannelHandler compressor = context.pipeline().get("compress");
		if (compressor instanceof CompressionEncoder) {
			PENDING.set(pending);
		} else {
			// No compression on this connection (a proxy in front, or compression turned off), or another
			// mod's compressor that TickEngine cannot see into: the raw size is all there is.
			if (compressor != null) {
				current.compressionUnmeasured.increment();
			}
			current.recordWire(pending, framed(rawBytes));
			PENDING.remove();
		}
	}

	/**
	 * Size on the wire: the packet plus the length that goes in front of it (a VarInt, 1 to 5 bytes).
	 * For the many tiny entity packets that length is about a tenth of the traffic.
	 */
	static int framed(int bytes) {
		int prefix = 1;
		for (int rest = bytes >>> 7; rest != 0; rest >>>= 7) {
			prefix++;
		}
		return bytes + prefix;
	}

	/** Called by CompressionEncoder with the compressed size of the packet just encoded on this thread. */
	public static void onCompressed(int wireBytes) {
		NetworkWindow.Pending pending = PENDING.get();
		if (pending == null) {
			return;
		}
		PENDING.remove();
		pending.window().recordWire(pending, framed(wireBytes));
	}

	private static NetworkWindow.Share attribute(ChannelHandlerContext context, Packet<?> packet) {
		if (packet instanceof ClientboundCustomPayloadPacket payload) {
			return share(channel(payload.getIdentifier()));
		}
		if (packet instanceof ClientboundCustomQueryPacket query) {
			// Login queries: Forge's handshake (fml:handshake) and mods that ask the client something before it joins.
			return share(channel(query.getIdentifier()));
		}
		if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
			BlockEntityType<?> type = blockEntity.getType();
			return share(source(NetworkSource.Kind.BLOCK_ENTITY, type, ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(type)),
				context, blockEntity.getPos());
		}
		if (packet instanceof ClientboundBlockUpdatePacket update) {
			return share(source(update.getBlockState().getBlock()), context, update.getPos());
		}
		if (packet instanceof ClientboundBlockEventPacket event) {
			return share(source(event.getBlock()), context, event.getPos());
		}
		if (packet instanceof ClientboundSoundPacket sound) {
			return share(source(sound.getSound()), context, sound.getX(), sound.getY(), sound.getZ());
		}
		if (packet instanceof ClientboundSoundEntityPacket sound) {
			return share(source(sound.getSound()));
		}
		if (packet instanceof ClientboundLevelParticlesPacket particles) {
			ParticleType<?> type = particles.getParticle().getType();
			return share(source(NetworkSource.Kind.PARTICLE, type, ForgeRegistries.PARTICLE_TYPES.getKey(type)),
				context, particles.getX(), particles.getY(), particles.getZ());
		}
		if (packet instanceof ClientboundAddEntityPacket add) {
			// Normally tagged by the tracker; this covers spawns sent some other way.
			return share(source(add.getType()), context, add.getX(), add.getY(), add.getZ());
		}
		NetworkSource.Kind kind = packet instanceof ClientboundLevelChunkWithLightPacket
			|| packet instanceof ClientboundLightUpdatePacket || packet instanceof ClientboundForgetLevelChunkPacket
			|| packet instanceof ClientboundChunksBiomesPacket ? NetworkSource.Kind.WORLD : NetworkSource.Kind.OTHER;
		return share(packetSource(kind, packet.getClass()));
	}

	/** A packet that changes several blocks of a chunk section, split by how many of each block it sets. */
	private static NetworkWindow.Share[] splitByBlock(ChannelHandlerContext context, ClientboundSectionBlocksUpdatePacket update) {
		Reference2LongOpenHashMap<Block> counts = new Reference2LongOpenHashMap<>();
		BlockPos[] first = new BlockPos[1];
		update.runUpdates((pos, state) -> {
			if (first[0] == null) {
				first[0] = pos.immutable();
			}
			counts.addTo(state.getBlock(), 1);
		});
		if (counts.isEmpty()) {
			return new NetworkWindow.Share[] { share(packetSource(NetworkSource.Kind.OTHER, update.getClass())) };
		}
		NetworkWindow.Share located = share(null, context, first[0]);
		NetworkWindow.Share[] shares = new NetworkWindow.Share[counts.size()];
		int i = 0;
		for (Reference2LongOpenHashMap.Entry<Block> entry : counts.reference2LongEntrySet()) {
			shares[i++] = new NetworkWindow.Share(source(entry.getKey()), entry.getLongValue(), located.chunk(), located.spot());
		}
		return shares;
	}

	/** A share with no place in the world (mod channels, chunk data, most vanilla packets). */
	private static NetworkWindow.Share share(NetworkSource source) {
		return new NetworkWindow.Share(source, 1, null, null);
	}

	private static NetworkWindow.Share share(NetworkSource source, ChannelHandlerContext context, BlockPos pos) {
		return share(source, context, pos.getX(), pos.getY(), pos.getZ());
	}

	private static NetworkWindow.Share share(NetworkSource source, ChannelHandlerContext context, double x, double y, double z) {
		return share(source, context, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
	}

	private static NetworkWindow.Share share(NetworkSource source, ChannelHandlerContext context, int x, int y, int z) {
		ResourceKey<Level> dimension = dimension(context);
		return dimension == null ? share(source)
			: new NetworkWindow.Share(source, 1, new NetworkWindow.ChunkKey(dimension, x >> 4, z >> 4),
				new NetworkSnapshot.Spot(x, y, z));
	}

	/**
	 * The dimension of the player this connection belongs to, or null for connections without a player.
	 * The player's level is read from a network thread; a player changing dimension at that moment can
	 * put a packet in the wrong one, which is acceptable for a profiler.
	 */
	private static ResourceKey<Level> dimension(ChannelHandlerContext context) {
		if (context.pipeline().get("packet_handler") instanceof Connection connection
			&& connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
			return listener.getPlayer().level().dimension();
		}
		return null;
	}

	// --- sources ---

	private static NetworkSource source(NetworkSource.Kind kind, Object key, ResourceLocation id) {
		return SOURCES.computeIfAbsent(key, ignored -> NetworkSource.of(kind, id));
	}

	/** A mod's channel. Keyed with its kind, so a sound with the same id stays a separate source. */
	private static NetworkSource channel(ResourceLocation id) {
		return source(NetworkSource.Kind.CHANNEL, List.of(NetworkSource.Kind.CHANNEL, id), id);
	}

	/** Packets that are about nothing in particular: named after their class, counted as vanilla. */
	private static NetworkSource packetSource(NetworkSource.Kind kind, Class<?> type) {
		return SOURCES.computeIfAbsent(type, ignored -> new NetworkSource(kind, NetworkWindow.packetName(type), "minecraft"));
	}

	private static NetworkSource source(EntityType<?> type) {
		return SOURCES.computeIfAbsent(type, ignored ->
			NetworkSource.of(NetworkSource.Kind.ENTITY, ForgeRegistries.ENTITY_TYPES.getKey(type)));
	}

	private static NetworkSource source(Block block) {
		return SOURCES.computeIfAbsent(block, ignored ->
			NetworkSource.of(NetworkSource.Kind.BLOCK, ForgeRegistries.BLOCKS.getKey(block)));
	}

	private static NetworkSource source(Holder<SoundEvent> sound) {
		// /playsound and some mods send a sound by its id instead of from the registry: no registry key then.
		ResourceLocation id = sound.unwrapKey().map(ResourceKey::location).orElseGet(() -> sound.value().getLocation());
		return SOURCES.computeIfAbsent(id, ignored -> NetworkSource.of(NetworkSource.Kind.SOUND, id));
	}
}
