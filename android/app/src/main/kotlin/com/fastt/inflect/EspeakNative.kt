package com.fastt.inflect

/**
 * Thin, serialized wrapper around `libinflect_espeak.so`.
 *
 * eSpeak-ng keeps its translator state in globals, so [textToPhonemes] is not reentrant -
 * every entry point here is `synchronized` on this object. Phonemization of a sentence is
 * sub-millisecond, so the lock is not a throughput concern next to the ONNX decode.
 */
internal object EspeakNative {

    @Volatile
    private var initialized = false

    // No @JvmStatic: these stay instance methods on the object, so the JNI symbols take a
    // jobject receiver. Adding it would change the expected native signature.
    private external fun nativeInit(dataPath: String): Int

    private external fun nativeTextToPhonemes(text: String): String?

    private external fun nativeTerminate()

    /**
     * Loads espeak-ng against [dataPath], a real directory (eSpeak reads its dictionaries with
     * `fopen`, so an asset path will not do - see `EspeakPhonemeSource.installData`).
     *
     * Idempotent; subsequent calls are no-ops.
     */
    @Synchronized
    fun ensureInitialized(dataPath: String) {
        if (initialized) return
        System.loadLibrary("inflect_espeak")
        val sampleRate = nativeInit(dataPath)
        check(sampleRate > 0) { "espeak_Initialize failed for data path $dataPath" }
        initialized = true
    }

    /** IPA for [text], with `_` phoneme separators, as `espeak_TextToPhonemes` returns them. */
    @Synchronized
    fun textToPhonemes(text: String): String {
        check(initialized) { "EspeakNative.ensureInitialized must be called first" }
        return nativeTextToPhonemes(text) ?: error("espeak_TextToPhonemes failed for: $text")
    }

    @Synchronized
    fun release() {
        if (!initialized) return
        nativeTerminate()
        initialized = false
    }
}
