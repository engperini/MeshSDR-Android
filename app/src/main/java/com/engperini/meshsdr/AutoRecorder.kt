package com.engperini.meshsdr

import java.io.OutputStream

/**
 * Optional automatic IQ recording: keeps the last few seconds of the raw rtl_tcp
 * stream in memory and, when the decoder reports any LoRa event, saves them plus
 * what follows, so a packet is on disk from its preamble on. Recording continues
 * until [postMillis] pass without a new event (capped at [maxMillis]).
 *
 * Call [add] with every chunk before feeding it to the decoder, and [trigger]
 * from the decoder callback.
 */
class AutoRecorder(
    private val open: (name: String) -> Output,
    private val log: (String) -> Unit,
    preBytes: Int = 3 * 2 * LoraProfile.SAMPLE_RATE,
    private val postMillis: Long = 4_000,
    private val maxMillis: Long = 30_000,
    private val maxFiles: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Where a recording goes; [close] finishes the file. */
    class Output(val stream: OutputStream, val location: String, val close: () -> Unit)

    private val ring = ByteArray(preBytes)
    private var ringPos = 0
    private var ringFilled = 0
    @Volatile private var triggered = false
    private var output: Output? = null
    private var started = 0L
    private var lastEvent = 0L
    private var written = 0L
    /** Bytes of the current chunk to drop so a file that starts with it starts on an I byte. */
    private var skipInChunk = 0
    var files = 0
        private set

    fun trigger() {
        triggered = true
    }

    /**
     * @param streamOffset bytes received since the rtl_tcp header before this chunk:
     * even = the chunk starts on an I byte. Files always start on one.
     * @param name file name to use if this chunk starts a recording.
     */
    fun add(buffer: ByteArray, length: Int, streamOffset: Long, name: () -> String) {
        val now = clock()
        if (triggered) {
            triggered = false
            lastEvent = now
            if (output == null) {
                if (files < maxFiles) start(streamOffset, name(), now)
                else if (files == maxFiles) {
                    files++
                    log(tr("Automatic recording: limit of $maxFiles files reached", "Gravação automática: limite de $maxFiles arquivos atingido"))
                }
            }
        }
        val out = output
        if (out == null) {
            remember(buffer, length)
            return
        }
        try {
            if (length > skipInChunk) out.stream.write(buffer, skipInChunk, length - skipInChunk)
            written += length - skipInChunk
            skipInChunk = 0
            if (now - lastEvent >= postMillis || now - started >= maxMillis) finish()
        } catch (e: Exception) {
            log(tr("Automatic recording", "Gravação automática") + ": ${e.message}")
            close()
        }
    }

    /** Ends a recording in progress, e.g. on disconnect. */
    fun close() {
        if (output != null) finish()
    }

    private fun start(streamOffset: Long, name: String, now: Long) {
        val out = try { open(name) } catch (e: Exception) {
            log(tr("Automatic recording", "Gravação automática") + ": ${e.message}")
            return
        }
        // The ring holds the bytes just before this chunk; drop one if they start on a Q byte.
        // An empty ring (right after another recording) means the file starts with this chunk.
        val skip = ((streamOffset - ringFilled) and 1L).toInt()
        skipInChunk = if (ringFilled == 0) skip else 0
        val first = (ringPos - ringFilled + skip + ring.size) % ring.size
        val count = if (ringFilled == 0) 0 else ringFilled - skip
        val tail = minOf(count, ring.size - first)
        try {
            out.stream.write(ring, first, tail)
            if (count > tail) out.stream.write(ring, 0, count - tail)
        } catch (e: Exception) {
            log(tr("Automatic recording", "Gravação automática") + ": ${e.message}")
            try { out.close() } catch (_: Exception) {}
            return
        }
        output = out
        started = now
        written = count.toLong()
        ringFilled = 0
        files++
        log(tr("Automatic recording started: ${out.location}", "Gravação automática iniciada: ${out.location}"))
    }

    private fun finish() {
        val out = output ?: return
        output = null
        try { out.close() } catch (_: Exception) {}
        log(tr("Automatic recording saved: $written bytes in ${out.location}",
            "Gravação automática salva: $written bytes em ${out.location}"))
    }

    private fun remember(buffer: ByteArray, length: Int) {
        var from = maxOf(0, length - ring.size)
        while (from < length) {
            val n = minOf(length - from, ring.size - ringPos)
            System.arraycopy(buffer, from, ring, ringPos, n)
            ringPos = (ringPos + n) % ring.size
            from += n
        }
        ringFilled = minOf(ring.size, ringFilled + length)
    }
}
