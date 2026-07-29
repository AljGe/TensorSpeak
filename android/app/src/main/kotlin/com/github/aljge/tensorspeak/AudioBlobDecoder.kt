package com.github.aljge.tensorspeak

/** Sniffs a TTS provider's response bytes and dispatches to the matching decoder. */
object AudioBlobDecoder {
    fun decode(bytes: ByteArray): DecodedAudio =
        if (WavPcmDecoder.looksLikeWav(bytes)) {
            WavPcmDecoder.decode(bytes)
        } else {
            Mp3PcmDecoder.decode(bytes)
        }
}
