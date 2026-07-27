// JNI bridge to eSpeak-ng's espeak_TextToPhonemes.
//
// This mirrors phonemizer's EspeakWrapper.text_to_phonemes (phonemizer/backend/espeak/
// wrapper.py) exactly, because the Python sandbox is the parity reference:
//
//   espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0)
//   espeak_SetVoiceByName("en-us")
//   while (textptr) phonemes = espeak_TextToPhonemes(&textptr, 1, ('_' << 8) | 0x02)
//   result = " ".join(chunks)
//
// textmode 1 = UTF-8 input. phonememode ('_' << 8) | 0x02 = IPA output with '_' as the
// phoneme separator; phonemizer's _postprocess_line collapses those separators afterwards.
//
// eSpeak-ng keeps global translator state, so none of this is reentrant. EspeakNative.kt
// serializes every call; do not call these from more than one thread.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define LOG_TAG "InflectEspeak"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Matches phonemizer's literal constants rather than re-deriving them from the enum.
#define ESPEAK_TEXT_MODE_UTF8 1
#define ESPEAK_PHONEME_MODE_IPA (((int) '_' << 8) | 0x02)

// Grows to fit the joined phoneme chunks for one call.
typedef struct {
	char *data;
	size_t length;
	size_t capacity;
} str_buffer;

static int buffer_append(str_buffer *buffer, const char *text, size_t length) {
	if (buffer->length + length + 1 > buffer->capacity) {
		size_t capacity = buffer->capacity ? buffer->capacity : 256;
		while (buffer->length + length + 1 > capacity) {
			capacity *= 2;
		}
		char *grown = realloc(buffer->data, capacity);
		if (grown == NULL) {
			return 0;
		}
		buffer->data = grown;
		buffer->capacity = capacity;
	}
	memcpy(buffer->data + buffer->length, text, length);
	buffer->length += length;
	buffer->data[buffer->length] = '\0';
	return 1;
}

JNIEXPORT jint JNICALL
Java_com_fastt_inflect_EspeakNative_nativeInit(JNIEnv *env, jobject self, jstring data_path) {
	(void) self;
	const char *path = (*env)->GetStringUTFChars(env, data_path, NULL);
	if (path == NULL) {
		return -1;
	}

	// AUDIO_OUTPUT_SYNCHRONOUS (0x02) is what phonemizer uses; no audio is ever produced
	// because we only call espeak_TextToPhonemes. Returns the sample rate on success.
	int sample_rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);
	(*env)->ReleaseStringUTFChars(env, data_path, path);
	if (sample_rate <= 0) {
		LOGE("espeak_Initialize failed (%d) - is espeak-ng-data present?", sample_rate);
		return -1;
	}

	espeak_ERROR error = espeak_SetVoiceByName("en-us");
	if (error != EE_OK) {
		LOGE("espeak_SetVoiceByName(en-us) failed (%d)", error);
		return -1;
	}
	return sample_rate;
}

JNIEXPORT jstring JNICALL
Java_com_fastt_inflect_EspeakNative_nativeTextToPhonemes(JNIEnv *env, jobject self, jstring text) {
	(void) self;
	const char *input = (*env)->GetStringUTFChars(env, text, NULL);
	if (input == NULL) {
		return NULL;
	}

	// espeak advances this pointer through the input, returning one chunk per clause.
	const void *cursor = (const void *) input;
	str_buffer buffer = {NULL, 0, 0};
	int failed = 0;

	while (cursor != NULL) {
		const char *chunk = espeak_TextToPhonemes(&cursor, ESPEAK_TEXT_MODE_UTF8,
		                                          ESPEAK_PHONEME_MODE_IPA);
		if (chunk == NULL || chunk[0] == '\0') {
			continue;
		}
		if (buffer.length > 0 && !buffer_append(&buffer, " ", 1)) {
			failed = 1;
			break;
		}
		if (!buffer_append(&buffer, chunk, strlen(chunk))) {
			failed = 1;
			break;
		}
	}

	(*env)->ReleaseStringUTFChars(env, text, input);
	if (failed) {
		free(buffer.data);
		return NULL;
	}

	jstring result = (*env)->NewStringUTF(env, buffer.data != NULL ? buffer.data : "");
	free(buffer.data);
	return result;
}

JNIEXPORT void JNICALL
Java_com_fastt_inflect_EspeakNative_nativeTerminate(JNIEnv *env, jobject self) {
	(void) env;
	(void) self;
	espeak_Terminate();
}
