from pathlib import Path

root = Path(__file__).resolve().parents[1]
provider = (root / "Miruro/src/main/kotlin/org/miruro/cloudstream/MiruroProvider.kt").read_text()
plugin = (root / "Miruro/src/main/kotlin/org/miruro/cloudstream/MiruroPlugin.kt").read_text()

checks = {
    "registered CloudStream provider": "registerMainAPI(MiruroProvider())" in plugin,
    "AniList search and catalog lookup": "anilist_id_in" in provider and "graphql.anilist.co" in provider,
    "episode listing endpoint": "v1/anime/$miruroId/episodes" in provider,
    "play endpoint": "episodes/$episodeNo/play" in provider,
    "separate sub/dub episode data": 'makeEpisodes("sub", subCount)' in provider and 'makeEpisodes("dub", dubCount)' in provider,
    "track-specific stream filtering": 'requestedVariant == "dub"' in provider and 'requestedVariant == "sub"' in provider,
    "native HLS/MP4 mapping": "ExtractorLinkType.M3U8" in provider and "ExtractorLinkType.VIDEO" in provider,
    "extractor fallback": "loadExtractor(embedUrl" in provider,
    "subtitle forwarding": "subtitleCallback(SubtitleFile" in provider,
    "no Cloudflare bypass implementation": all(x not in provider.lower() for x in ("curl_cffi", "tls impersonat", "cloudscraper", "turnstile solver")),
}
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + "  " + name)
if not all(checks.values()):
    raise SystemExit(1)
