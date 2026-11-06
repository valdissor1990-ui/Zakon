# Security notes for the personal cabinet

## What is protected

- Case titles, notes, timestamps and attachment references are stored as AES-256-GCM authenticated ciphertext in app-private preferences.
- Imported attachments are copied into app-private storage and encrypted with AES-256-GCM. The IV is stored with each encrypted file; GCM authentication is checked before a decrypted file is returned to an external viewer.
- The encryption key is generated inside Android Keystore and is non-exportable. StrongBox is requested when available; the app falls back to the device Keystore/TEE when StrongBox is unavailable.
- On Android 11 and later, key use requires recent Android system authentication (strong biometric or device credential). The app PIN is an additional local access gate, not the Android system credential.
- The PIN verifier uses PBKDF2-HMAC-SHA256 with 600,000 iterations, a per-install random salt, and constant-time comparison. Older verifier formats are upgraded after a successful login.
- Repeated incorrect PIN attempts cause increasing delays. The delay is enforced by the app and is not a substitute for Android Keystore protection.
- Legacy encrypted cabinet metadata and attachment files are migrated when possible. Files that cannot be migrated are retained as unavailable references rather than silently discarded.
- Decrypted attachment viewing copies are placed in app-private cache and cleared when the cabinet screen resumes and when a new storage instance is created.

## Platform differences

- Android 11+ requires system authentication for cryptographic key operations.
- Older supported Android versions continue to use the non-exportable Android Keystore key, but do not receive the same per-operation biometric/device-credential gate.
- The app reports the actual Keystore security level when it can be queried; requesting StrongBox does not guarantee that a device provides it.

## Important limitations

- An external app used to view an attachment receives a temporary read grant and may copy or export the content. The app cannot control what that external viewer does.
- Rooted or compromised devices, malicious accessibility services, screen capture by privileged software, and malware running with control of an unlocked device are outside the protection boundary.
- Clearing app data or uninstalling the app can remove the Android Keystore key and make encrypted cabinet data unrecoverable. Keep a separate secure copy of important source documents.
- This implementation has not yet been validated on a physical device across the supported Android versions. CI passing is necessary but does not replace device testing.

## Validation required before release

1. Create a new cabinet PIN, close/reopen the app, and authenticate using PIN and system credential/biometrics.
2. Verify that incorrect PIN attempts trigger increasing delays and that a correct PIN clears the delay.
3. Upgrade an installation containing the previous PIN verifier, encrypted metadata, and encrypted attachments.
4. Import, open, and remove both images and documents; verify files remain encrypted at rest.
5. Corrupt a test ciphertext and confirm that authentication failure does not return partial plaintext or overwrite saved data.
6. Test on Android 10 and Android 11+ devices, including a device without StrongBox.
