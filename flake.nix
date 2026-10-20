{
  description = "Libremote Android TV Service: a private, open-source phone remote for Android TV and Google TV";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        # The Android SDK is unfree and ships under Google's SDK license.
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      buildToolsVersion = "36.1.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeCmake = false;
        includeEmulator = false;
        includeNDK = false;
        includeSources = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      # Emulators for testing (`nix develop .#emulator`): a phone to run the app and an Android TV with
      # Google's TV Remote Service to pair with. See README.
      emulators = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "34" "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeCmake = false;
        includeNDK = false;
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "android-tv" "google_apis" ];
        abiVersions = [ "x86" "x86_64" ];
      };
      # Android TV 8.1 and 9 (`nix develop .#emulator-old-tv`), the oldest TVs Libremote Receiver supports. A
      # composition of its own: adding these to the one above would multiply every version by every image type.
      oldTvEmulators = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "26" "28" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeCmake = false;
        includeNDK = false;
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "android-tv" ];
        abiVersions = [ "x86" ];
      };
      emulatorShell = composition: pkgs.mkShell {
        packages = [ pkgs.jdk21 composition.platform-tools ];
        JAVA_HOME = pkgs.jdk21.home;
        ANDROID_HOME = "${composition.androidsdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${composition.androidsdk}/libexec/android-sdk";
        # avdmanager and the emulator are not on PATH in the composed SDK.
        shellHook = ''
          export PATH="$(echo $ANDROID_HOME/cmdline-tools/*/bin):$ANDROID_HOME/emulator:$PATH"
        '';
      };
    in {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk21 android.platform-tools ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          # AGP's Maven aapt2 is a generic-Linux binary that needs nix-ld; use the SDK's, which nixpkgs patches.
          # AGP calls that option experimental, hence the warning suppression.
          GRADLE_OPTS = builtins.concatStringsSep " " [
            "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2"
            "-Dorg.gradle.project.android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE"
          ];
        };

        emulator = emulatorShell emulators;

        emulator-old-tv = emulatorShell oldTvEmulators;
      };
    };
}
