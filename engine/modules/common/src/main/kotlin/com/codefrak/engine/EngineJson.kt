package com.codefrak.engine

import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The engine's default, unconfigured, immutable Jackson 3 mapper. Shared because a
 * `tools.jackson` `ObjectMapper` is thread-safe: every class that would otherwise build its
 * own identical copy uses this one instead.
 */
val engineJson = jacksonObjectMapper()
