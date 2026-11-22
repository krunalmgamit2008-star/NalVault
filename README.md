# Nal Vault

Offline AES-256 vault for Android. Files are encrypted with a key derived from your master
password (PBKDF2, 120,000 iterations); the key is never written to disk. The index - names,
sizes and folders - is encrypted with the same key.

- AES-256-GCM, random IV per file
- 12 character minimum master password
- Auto-lock when the app leaves the foreground
- No screenshots or screen recording (FLAG_SECURE)
- Photos, videos and audio play inside the app
- Decrypted copies are deleted as soon as they are no longer needed
- No network, no analytics, no cloud

## Build

Push to GitHub, then run the **Build Nal Vault** workflow (Actions tab) and download the
`nal-vault-apk` artifact.
