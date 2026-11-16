# Virex Music 1.3.3 verification — 16 November 2026

Build: testDebugUnitTest lintDebug assembleRelease — PASSED.
28 host Android/Compose/Media3 tests, zero failures/errors. Android lint: zero errors, 11 advisory warnings. Release vital lint passed.

The default music API is https://jiosaavn-api.n4noobff.workers.dev/api. Search and song parsing use the existing schema. The released DEX contains the new endpoint and no old jiosavan-api2.vercel.app endpoint. Lyrics retain their existing provider. 

Live checks with the app's configured User-Agent:
- Search for Lover and Arijit Singh: HTTP 200, expected data.results structure.
- Returned qualities: 12, 48, 96, 160 and 320 kbps.
- A returned 320 kbps audio URL: HTTP 206, audio/mp4, 1024 bytes read with a range request. This is a reachability check, not full playback/decoding verification.
- Artist fallback query: HTTP 200, 20 results, 19 usable candidates beyond the seed.
- Suggestions for two different song IDs: HTTP 500, provider message "Cannot read properties of undefined (reading 'id')". This is an observed provider limitation. Existing retry/artist-search radio fallback is retained; its regression test passed.

New test uses a recorded successful response from the new API through a local TLS test server and the real MusicApi.search/Song parser; it verifies titles, artists, artwork, duration and HTTPS 320kbps URLs. Existing tests cover radio fallback, lyrics, playlist downloads, offline persistence/failure cleanup, responsive player layouts, collections, queue edits and native session restoration.

Package com.virexdl.app; version code 7 / name 1.3.3. Signed release certificate SHA-256: dbcf82a7adb8378518abbdad2889670c5c4f2b464a0f2ecb358a817d2cdd3755 — unchanged from 1.3.2 . Version 1.3.2 can be updated in place; 1.3.1 and earlier use the lost older key and require a fresh installation.

No physical-device test was performed. Live provider behavior can change, and host tests do not establish hardware decoding/audio-focus/OEM-specific behavior on every phone.

Release APK SHA-256: 156d735b6bc1f1111d1f2bb88ec5cace3b06e1ad6b8f825bde9041b6189f231a
