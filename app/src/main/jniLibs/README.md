# Mining engine binary

This app launches a real mining engine (xmrig) as a subprocess; it does not
ship one. Android only packages native code from
`app/src/main/jniLibs/<abi>/`, and only files matching `lib*.so` get
extracted to disk as an executable file at install time — that's why the
engine has to be named `libxmrig.so` here even though it's a real
executable, not a loadable shared library.

## Option A: use the CI workflow (recommended)

`.github/workflows/build-xmrig-android.yml` cross-compiles xmrig (and its
libuv dependency) for `arm64-v8a` and `armeabi-v7a` using the Android NDK,
entirely on GitHub-hosted runners.

1. On GitHub, go to **Actions → Build xmrig for Android → Run workflow**
   (optionally override the `xmrig_ref`/`libuv_ref` inputs to pin a
   different version; defaults to xmrig v6.26.0 / libuv v1.48.0).
2. When it finishes, download the `libxmrig-arm64-v8a` and
   `libxmrig-armeabi-v7a` artifacts.
3. Place each `libxmrig.so` at:
   - `app/src/main/jniLibs/arm64-v8a/libxmrig.so`
   - `app/src/main/jniLibs/armeabi-v7a/libxmrig.so`

**Tradeoff:** the workflow builds with `-DWITH_TLS=OFF` to avoid also
cross-compiling OpenSSL, so the resulting binary only speaks a pool's
plaintext `stratum+tcp` port, not an SSL-only one. Most public Monero pools
expose both; just point the app's pool field at the non-SSL port/hostname.
If you need TLS, the workflow's xmrig `cmake` step is the place to add a
cross-compiled OpenSSL and switch `WITH_TLS` back to `ON`.

## Option B: build it yourself

1. Get xmrig from the official upstream project:
   https://github.com/xmrig/xmrig
2. Cross-compile with the Android NDK (see xmrig's own build docs for
   cross-compilation) or build it inside Termux, for `arm64-v8a` /
   `armeabi-v7a`.
3. Verify the resulting binary against xmrig's published checksums/release
   signing before using it — it runs with this app's permissions.
4. Place it per ABI as in Option A above.

## Either way

Rebuild the APK afterward. `MiningService` looks for the binary at
`applicationInfo.nativeLibraryDir/libxmrig.so` at runtime and reports
"Engine missing" if it isn't there.

Don't download and embed a prebuilt binary from a random third party —
build it yourself from source (Option A or B above), and verify it before
shipping it inside an app.
