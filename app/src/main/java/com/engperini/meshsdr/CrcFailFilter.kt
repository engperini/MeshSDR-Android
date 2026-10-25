package com.engperini.meshsdr

/**
 * With decoder diversity, the primary decoder reports a frame whose CRC failed
 * while an extra decoder (another decimation phase, filter or drift tracking)
 * decodes the same frame from the same samples a few milliseconds later. This
 * drops such a "bad CRC" event when a CRC-ok frame of the same declared length comes
 * within [windowMs] on either side; other "bad CRC" events are passed on once
 * the window has passed ([flush]). All other events go through at once.
 *
 * Not thread-safe: called from the thread that feeds the decoder.
 */
class CrcFailFilter(
    private val emit: (LoraFrame) -> Unit,
    private val windowMs: Long = 600,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Held(val frame: LoraFrame, val at: Long)

    private val held = ArrayList<Held>()
    /** Length → when a CRC-ok frame of that length was last passed on. */
    private val decodedAt = HashMap<Int, Long>()

    fun accept(frame: LoraFrame) {
        val now = clock()
        flush(now)
        when {
            frame.status == LoraFrame.CRC_FAIL -> {
                val seen = decodedAt[frame.declaredLength]
                if (seen == null || now - seen > windowMs) held += Held(frame, now)
            }
            frame.verified -> {
                held.removeAll { it.frame.declaredLength == frame.declaredLength }
                decodedAt[frame.declaredLength] = now
                emit(frame)
            }
            else -> emit(frame)
        }
    }

    /** Passes on the "bad CRC" events whose window has ended. */
    fun flush(now: Long = clock()) {
        if (held.isEmpty()) return
        val due = held.filter { now - it.at > windowMs }
        if (due.isEmpty()) return
        held.removeAll(due)
        due.forEach { emit(it.frame) }
    }

    /** Passes on everything still held (e.g. when the receiver closes). */
    fun drain() {
        val all = held.toList()
        held.clear()
        all.forEach { emit(it.frame) }
    }
}
