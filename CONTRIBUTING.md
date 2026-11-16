# Contributing

Open an issue before substantial changes. Describe the problem, expected behavior, app/Android version and device model. Keep pull requests focused and include relevant validation.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before submitting. Use a connected device for `./gradlew connectedDebugAndroidTest` when the change needs real Android verification. Changes to queue state, session restore, downloads or API parsing should preserve regression coverage.

Never commit private signing keys, signing.properties, passwords, tokens, personal backups or downloaded music. Use your own key for forked APKs. Project code contributions are under the repository's MIT License.
