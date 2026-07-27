{
  pkgs,
  lib,
  ...
}: let
  # Export INFLECT_ANDROID=0 before `direnv reload` to skip the multi-GB SDK/NDK download
  # when you only need the Stage 1 Python sandbox.
  withAndroid = (builtins.getEnv "INFLECT_ANDROID") != "0";
in {
  # --- Stage 1: Python sandbox -------------------------------------------------
  # See https://devenv.sh/languages/python/
  languages.python = {
    enable = true;
    package = pkgs.python311;
    # venv.enable puts .devenv/state/venv on PATH; uv.sync keeps it in step with pyproject.toml
    venv.enable = true;
    uv.enable = true;
    uv.sync.enable = true;
  };

  packages =
    [
      # Cross-check the bundled espeakng-loader phonemes against a system espeak-ng
      pkgs.espeak-ng
      # soxi/sox for inspecting generated 24 kHz wavs
      pkgs.sox
      pkgs.git
    ]
    # The android module brings the SDK/NDK/JDK but not Gradle itself.
    ++ lib.optional withAndroid pkgs.gradle;

  # AGP 8.7 requires JDK 17+; pin it rather than inheriting whatever the default is.
  languages.java = lib.mkIf withAndroid {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  # manylinux wheels (onnxruntime, soundfile, espeakng-loader) dlopen system libs
  env.LD_LIBRARY_PATH = lib.makeLibraryPath [
    pkgs.stdenv.cc.cc.lib
    pkgs.zlib
    pkgs.libsndfile
    pkgs.espeak-ng
  ];

  # --- Stage 2: Android --------------------------------------------------------
  # devenv's own module accepts the SDK licence, patches aapt2 for Gradle and writes
  # local.properties, which is why we use it instead of calling androidenv by hand.
  # NDK + CMake are enabled now, ahead of the Stage 3 espeak-ng native build.
  android = lib.mkIf withAndroid {
    enable = true;
    platforms.version = ["35"];
    buildTools.version = ["35.0.0"];
    ndk.enable = true;
    cmake.version = ["3.22.1"];
    emulator.enable = false;
    systemImages.enable = false;
    abis = ["arm64-v8a" "x86_64"];
  };

  scripts.check-env.exec = ''
    echo "Python version: $(python --version)"
    echo "uv version: $(uv --version)"
    echo "Python executable: $(python -c 'import sys; print(sys.executable)')"
    echo "VIRTUAL_ENV: $VIRTUAL_ENV"
    echo "ANDROID_HOME: ''${ANDROID_HOME:-<not enabled>}"
  '';

  enterShell = ''
    echo "--- TensorSpeak (Inflect Micro/Nano ONNX) ---"
    check-env
    echo ""
    echo "  python scripts/fetch_model.py      # download micro+nano -> models/<variant>/"
    echo "  python scripts/inspect_graphs.py   # -> docs/TENSOR_CONTRACT.md"
    echo "  python scripts/synthesize.py --text 'Hello world.' --output out/sample.wav"
    echo "  python scripts/parity_check.py [--model nano]  # bit-parity vs upstream"
    ${lib.optionalString withAndroid ''
      echo "  (cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest)"
    ''}
    echo "---------------------------------"
  '';
}
