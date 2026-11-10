# Miruro CloudStream extension

Standalone CloudStream provider targeting `https://www.miruro.cx`.

## Implemented in this source snapshot
- AniList-backed search, popular/trending home rows and anime metadata.
- Miruro REST catalogue lookup by AniList ID.
- Episode pagination and separate Subbed/Dubbed episode groups based on Miruro's episode counts.
- Track-specific loading (`sub`/`ssub` versus `dub`).
- Direct HLS/MP4 stream mapping, quality labels, Referer and additional request headers.
- Subtitle forwarding to CloudStream.
- Embed URL fallback through CloudStream's registered extractor APIs where supported.
- Catalog response decoding for Miruro's documented XOR-plus-gzip response format.

## Important limitations / validation status
- The API contract is based on a third-party open-source client implementation and can change without notice.
- This environment could not resolve external hosts, so live requests to `miruro.cx` and AniList were not possible.
- Gradle is not installed in the environment and the Gradle distribution could not be downloaded. Therefore **the Android/CloudStream plugin has not been compiled** here.
- `tests/smoke_test.py` checks source structure and expected code paths only. It is not a Kotlin compile test or a live playback test.
- Some iframe-only servers may remain unavailable if CloudStream has no compatible extractor.
- No Cloudflare challenge bypass, TLS fingerprint spoofing, proxy rotation or access-control circumvention is included.

## Build
The GitHub Actions workflow installs JDK 17, Android SDK and Gradle 8.9, then runs `gradle make makePluginsJson`.
