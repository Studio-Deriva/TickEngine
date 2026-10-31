#!/usr/bin/env python3
"""Asks a Minecraft server for its status (the server list ping) and prints the reply's length.

Used by smoke-test.sh: it opens a real connection, so the server encodes and sends packets and the
network profiler has something to measure without a game client. Standard library only.

Usage: tools/status-ping.py [host] [port]      defaults: localhost 25565
"""
import socket
import struct
import sys


def varint(value):
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out += bytes([byte | 0x80])
        else:
            return out + bytes([byte])


def read_varint(sock):
    value = 0
    for shift in range(0, 35, 7):
        byte = sock.recv(1)
        if not byte:
            raise ConnectionError("connection closed")
        value |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return value
    raise ValueError("varint too long")


def packet(packet_id, payload=b""):
    body = varint(packet_id) + payload
    return varint(len(body)) + body


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "localhost"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565
    address = host.encode()
    # Handshake: protocol version (any number works for a status request), address, port, next state 1 (status).
    handshake = varint(767) + varint(len(address)) + address + struct.pack(">H", port) + varint(1)
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.sendall(packet(0x00, handshake) + packet(0x00))
        length = read_varint(sock)
        received = 0
        while received < length:
            chunk = sock.recv(length - received)
            if not chunk:
                raise ConnectionError("connection closed")
            received += len(chunk)
    print(f"status reply: {length} bytes")


if __name__ == "__main__":
    main()
