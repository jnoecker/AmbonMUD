package dev.ambon.redis

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ambon.persistence.jsonMapper

/** Shared JSON ObjectMapper for Redis serialization. Delegates to [jsonMapper] to avoid duplicate configuration. */
val redisObjectMapper: ObjectMapper = jsonMapper
