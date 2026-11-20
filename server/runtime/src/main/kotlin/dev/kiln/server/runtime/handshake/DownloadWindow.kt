package dev.kiln.server.runtime.handshake

/** Bytes [used] by one address in the window that began at [start]. */
internal class DownloadWindow(var start: Long, var used: Long)
