{
  description = "TensorSpeak: on-device TTS powered by Inflect Micro/Nano ONNX";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable"; # Or a specific Nixpkgs release
    devenv.url = "github:cachix/devenv";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    devenv,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      # The Android SDK is unfree and needs explicit license acceptance. Setting it here
      # instantiates nixpkgs once; doing it inside devenv.nix would force a second full
      # nixpkgs evaluation on every shell entry (devenv runs with the eval cache off).
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
    in {
      devShells.default = devenv.lib.mkShell {
        inherit pkgs;
        modules = [
          # Import the local devenv.nix configuration
          ./devenv.nix
        ];
      };
    });
}
