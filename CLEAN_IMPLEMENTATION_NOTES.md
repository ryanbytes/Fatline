# Clean implementation notes

FatLine is an independent Android client implementation based on public interoperability behavior and the published ThinLine server/API implementation. It does not incorporate ThinLine mobile application source, branding, art, or package identity.

Verified interoperability behavior used by FatLine includes the JSON-array WebSocket envelope, `PIN` Base64 authentication, `CFG` system/talkgroup references, nested `LFM` selection map, `LCL` search, `CAL` call retrieval, and ThinLine's published relay audio-key scheme.
