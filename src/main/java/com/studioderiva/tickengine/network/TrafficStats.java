package com.studioderiva.tickengine.network;

import java.util.concurrent.atomic.LongAdder;

/**
 * Packet and byte totals. Written from the network threads (one per group of connections) at the
 * same time, so every counter is a {@link LongAdder}.
 */
class TrafficStats {
	final LongAdder packets = new LongAdder();
	/** Bytes of the encoded packet, before compression. */
	final LongAdder rawBytes = new LongAdder();
	/** Bytes on the wire: after compression, with the length in front of each packet. */
	final LongAdder wireBytes = new LongAdder();

	void addPacket(long raw) {
		packets.increment();
		rawBytes.add(raw);
	}

	void addWire(long wire) {
		wireBytes.add(wire);
	}
}
