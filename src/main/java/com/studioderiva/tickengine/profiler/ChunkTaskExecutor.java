package com.studioderiva.tickengine.profiler;

/**
 * Marker that ChunkTaskExecutorMixin adds to the chunk source's server-thread executor. On 1.20.1
 * that class does not override managedBlock, so the wait is wrapped in BlockableEventLoop and this
 * tells the chunk executor apart from the server's own.
 */
public interface ChunkTaskExecutor {
}
