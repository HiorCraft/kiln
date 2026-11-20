package dev.kiln.example.server.shop.lua

/** Tokens left and when they were last refilled (epoch millis). */
internal class TokenBucket(var tokens: Double, var at: Long)
