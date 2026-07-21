# Releasing AniLili+

AniLili+ checks the latest release in `nassim-arifette/anilili` when the app starts. Successful
automatic checks are throttled to once every 12 hours. When a newer semantic version is available,
the app prompts the user to download the signed APK and then to open Android's package installer.
Android requires user interaction and approval for these steps; silent installation is not
available to a normal application.

## Signing identity

Every published APK must use both of these permanent identities:

- application id: `com.nassimarifette.anililiplus`
- the same release keystore and key alias

The application id is intentionally different from the original project's `com.miruronative`.
The original author's signing key is not available to this fork, so Android cannot treat the two
projects as updates of each other. AniLili+ can be installed beside the original app, and future
AniLili+ releases can update it without deleting local data.

The original application's private data is not imported: history and resume positions, local
watchlist, settings, encrypted AniList/MyAnimeList sessions, reminders, notification channels, and
Android TV Watch Next entries all belong to the old Android application id. Users must sign in and
configure AniLili+ once. Keeping both apps active can produce duplicate notifications and TV rows,
so disable reminders in or uninstall the original after checking that AniLili+ is ready.

Back up the release keystore and its passwords somewhere secure. Losing the key permanently removes
the ability to update existing installations. Do not commit the keystore or its passwords.

## GitHub Actions secrets

Create a GitHub Actions environment named `release`. The `Publish signed APK` workflow needs these
environment secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the complete release `.jks` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Alias of the signing key |
| `ANDROID_KEY_PASSWORD` | Signing-key password |

The public release-certificate SHA-256 digest is pinned directly in the reviewed workflow. Changing
the signing identity therefore requires an explicit source change, not an editable variable.

Example encoding commands:

```bash
base64 -w 0 release-signing.jks
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-signing.jks"))
```

## Publish a version

1. Increase both `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge and push the change to `main`.
3. Run **Actions > Publish signed APK > Run workflow**, or push a tag matching the Gradle version,
   such as `v0.2.1`.
4. Confirm that the workflow tests the app and publishes exactly one release APK plus its SHA-256
   checksum.

The workflow only accepts the exact current `main` commit and a three-part numeric version. It
builds and tests without access to the signing key, aligns and signs in a separate protected job
that never executes repository code, refuses to replace an existing release, and requires both
`versionCode` and the semantic `versionName` to exceed the preceding release. It checks the
application id, version code, version name, alignment, single signer, and pinned signing certificate
before creating `AniLili+ v<version>`.

The in-app updater requires the matching exact APK asset and GitHub SHA-256 digest, then checks the
downloaded package, version code, and signing certificate before enabling Android's installer.

For a local signed build, create an ignored `keystore.properties` at the repository root:

```properties
storeFile=release-signing.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `./gradlew testDebugUnitTest assembleRelease`. The APK is written to
`app/build/outputs/apk/release/anilili-plus.apk`.

`keystore.properties.example` is a safe template. It contains no usable credentials.
