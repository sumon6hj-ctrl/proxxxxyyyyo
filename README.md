# Sulav Proxy — GitHub Android App

Professional dark/purple Android utility inspired by the supplied UI references.

### Screens
- Capture Console — local HTTP forwarder, start/stop, packet history and request details
- Request Send — GET / POST / PUT / DELETE for endpoints you control
- Protobuf / HEX Decoder — generic byte/HEX inspection and copy
- Updates — version/update status placeholder
- Account — local profile page

### Performance
- No third-party runtime libraries
- Java 17 + Android SDK 35
- Background executor for network requests
- Bounded HEX capture to keep memory use low
- Simple Android Views for fast startup and reliable GitHub builds

### GitHub Actions
The workflow builds `app-debug.apk`, verifies it exists, uploads it as **SulavProxy-debug-apk**, and prints the GitHub Actions run link in the job log and Summary.

The Python source supplied with the project was used as a reference for its HTTP server, `/download` configuration response, forwarding and logging concepts. Credential/token extraction and interception of third-party game traffic are intentionally excluded.
