# Mining engine binary

This app launches a real mining engine (xmrig) as a subprocess; it does not
ship one. Android only packages native code from
`app/src/main/jniLibs/<abi>/`, and only files matching `lib*.so` get
extracted to disk as an executable file at install time — that's why the
engine has to be named `libxmrig.so` here even though it's a real
executable, not a loadable shared library.

## Steps

1. Get xmrig from the official upstream project:
   https://github.com/xmrig/xmrig
   There's no official prebuilt "Android" binary upstream; the usual path
   is cross-compiling it yourself with the Android NDK (see xmrig's own
   build docs for cross-compilation) or building it inside Termux for
   `arm64-v8a` / `armeabi-v7a`.
2. Verify the resulting binary against xmrig's published checksums/release
   signing before using it — it runs with this app's permissions.
3. Place it here, per ABI you want to support:
   - `app/src/main/jniLibs/arm64-v8a/libxmrig.so`
   - `app/src/main/jniLibs/armeabi-v7a/libxmrig.so`
4. Rebuild the APK. `MiningService` looks for the binary at
   `applicationInfo.nativeLibraryDir/libxmrig.so` at runtime and reports
   "Engine missing" if it isn't there.

Don't download and embed a prebuilt binary from a random third party —
build it yourself from source, or fetch it directly from the xmrig GitHub
releases/repo, and verify it before shipping it inside an app.
