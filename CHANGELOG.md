# Changelog

## [Unreleased]

## [1.0.0]

### Added

- Apple On-Device AI plugin: exposes Apple's FoundationModels as OpenAI-compatible REST API
- OpenAI-compatible REST endpoint (`/v1/chat/completions`) on a configurable host and port
- Streaming and non-streaming chat completions
- Settings UI for host, port, Swift helper path, and auto-start
- Build Helper Automatically: compiles Swift binary from bundled sources (requires Xcode)
- Custom port server manager for running the endpoint on a user-configured port
- Toggle Server action in the Tools menu to start/stop the server
- Auto-start option to launch the server when the IDE starts
- API key authentication support
- macOS-only (Apple Silicon, macOS 26+ with Apple Intelligence)

[Unreleased]: https://github.com/rock3r/apple-intelligence-plugin/compare/1.0.0...HEAD
[1.0.0]: https://github.com/rock3r/apple-intelligence-plugin/commits/1.0.0
