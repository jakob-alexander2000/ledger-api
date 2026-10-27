package com.ledger.custody.shared

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** JDBC timestamps round-trip at microsecond resolution. Truncate before persisting. */
fun Clock.now(): Instant = instant().truncatedTo(ChronoUnit.MICROS)
