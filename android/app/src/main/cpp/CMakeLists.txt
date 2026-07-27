cmake_minimum_required(VERSION 3.22.1)
project(inflect_espeak C)

# eSpeak-ng 1.52.0, vendored as a submodule. We deliberately do NOT add_subdirectory() the
# upstream tree: its top-level CMakeLists pulls libsonic over the network via FetchContent and
# builds the speak-ng binary, the dictionaries, docs and tests - none of which we need. This
# file mirrors src/libespeak-ng/CMakeLists.txt and src/ucd-tools/CMakeLists.txt instead.
#
# Everything optional is off:
#   USE_ASYNC        - we only ever call espeak_TextToPhonemes, never synthesize
#   USE_KLATT        - "
#   USE_SPEECHPLAYER - "  (and it is C++, which would force an STL onto the build)
#   USE_MBROLA / USE_LIBSONIC / USE_LIBPCAUDIO - no audio output path at all
set(ESPEAK_NG_VERSION "1.52.0")
set(ESPEAK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng")

if(NOT EXISTS "${ESPEAK_ROOT}/src/libespeak-ng/speech.c")
  message(FATAL_ERROR
    "espeak-ng sources missing - run: git submodule update --init --recursive")
endif()

set(HAVE_MKSTEMP 1)
set(USE_ASYNC 0)
set(USE_KLATT 0)
set(USE_LIBPCAUDIO 0)
set(USE_LIBSONIC 0)
set(USE_MBROLA 0)
set(USE_SPEECHPLAYER 0)

set(ESPEAK_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/espeak-config")
configure_file(config.h.in "${ESPEAK_CONFIG_DIR}/config.h")

add_library(ucd STATIC
  "${ESPEAK_ROOT}/src/ucd-tools/src/case.c"
  "${ESPEAK_ROOT}/src/ucd-tools/src/categories.c"
  "${ESPEAK_ROOT}/src/ucd-tools/src/ctype.c"
  "${ESPEAK_ROOT}/src/ucd-tools/src/proplist.c"
  "${ESPEAK_ROOT}/src/ucd-tools/src/scripts.c"
  "${ESPEAK_ROOT}/src/ucd-tools/src/tostring.c"
)
set_property(TARGET ucd PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(ucd PUBLIC "${ESPEAK_ROOT}/src/ucd-tools/src/include")

add_library(espeak-ng STATIC
  "${ESPEAK_ROOT}/src/libespeak-ng/common.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/mnemonics.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/error.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/ieee80.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/compiledata.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/compiledict.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/dictionary.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/encoding.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/intonation.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/langopts.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/numbers.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/phoneme.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/phonemelist.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/readclause.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/setlengths.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/soundicon.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/spect.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/ssml.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/synthdata.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/synthesize.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/tr_languages.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/translate.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/translateword.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/voices.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/wavegen.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/speech.c"
  "${ESPEAK_ROOT}/src/libespeak-ng/espeak_api.c"
)
set_property(TARGET espeak-ng PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(espeak-ng
  BEFORE PRIVATE
  "${ESPEAK_CONFIG_DIR}"
  "${ESPEAK_ROOT}/src/include"
  "${ESPEAK_ROOT}/src/include/compat"
  "${ESPEAK_ROOT}/src/libespeak-ng"
)
target_include_directories(espeak-ng PUBLIC "${ESPEAK_ROOT}/src/include")
target_compile_definitions(espeak-ng PRIVATE
  "LIBESPEAK_NG_EXPORT=1"
  # Never used: EspeakNative always passes an explicit data path from the app's filesDir.
  "PATH_ESPEAK_DATA=\"/data/local/tmp/espeak-ng-data\""
)
target_compile_options(espeak-ng PRIVATE
  -fno-exceptions
  -fwrapv
  -Wno-unused-parameter
  -Wno-unused-but-set-variable
)
target_link_libraries(espeak-ng PRIVATE ucd m)

add_library(inflect_espeak SHARED inflect_espeak_jni.c)
target_compile_definitions(inflect_espeak PRIVATE "LIBESPEAK_NG_EXPORT=1")
target_link_libraries(inflect_espeak PRIVATE espeak-ng log)
