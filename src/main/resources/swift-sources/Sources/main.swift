// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

// Swift helper for Apple on-device AI (FoundationModels framework).
// Communicates with the Kotlin host via JSON over stdin/stdout.
// Requires macOS 26+ with Apple Intelligence enabled.

import Foundation
import FoundationModels

// MARK: - JSON Models

struct JsonRequest: Codable {
    let action: String
    let messages: [JsonMessage]?
    let temperature: Double?
    let maxTokens: Int?
    let stream: Bool?
}

struct JsonMessage: Codable {
    let role: String
    let content: String
}

struct JsonResponse: Codable {
    let type: String // "result", "error", "stream_delta", "stream_done", "models", "status"
    let content: String?
    let error: String?
    let modelAvailable: Bool?
    let reason: String?
    let finishReason: String?
}

// MARK: - Shared Helpers

private func buildSession(from messages: [JsonMessage]) throws -> (session: LanguageModelSession, lastMessage: String) {
    guard !messages.isEmpty else {
        throw NSError(domain: "AppleAI", code: 2, userInfo: [NSLocalizedDescriptionKey: "No messages provided"])
    }

    let lastMessage = messages.last!
    let previousMessages = messages.count > 1 ? Array(messages.dropLast()) : []

    var entries: [Transcript.Entry] = []
    for msg in previousMessages {
        let textSegment = Transcript.TextSegment(content: msg.content)
        switch msg.role.lowercased() {
        case "system":
            let instructions = Transcript.Instructions(segments: [.text(textSegment)], toolDefinitions: [])
            entries.append(.instructions(instructions))
        case "user":
            let prompt = Transcript.Prompt(segments: [.text(textSegment)])
            entries.append(.prompt(prompt))
        case "assistant":
            let response = Transcript.Response(assetIDs: [], segments: [.text(textSegment)])
            entries.append(.response(response))
        default:
            let prompt = Transcript.Prompt(segments: [.text(textSegment)])
            entries.append(.prompt(prompt))
        }
    }

    let transcript = Transcript(entries: entries)
    let session = LanguageModelSession(transcript: transcript)
    return (session, lastMessage.content)
}

private func buildOptions(temperature: Double?, maxTokens: Int?) -> GenerationOptions {
    if let temp = temperature {
        return GenerationOptions(temperature: temp, maximumResponseTokens: maxTokens)
    } else if let maxTokens = maxTokens {
        return GenerationOptions(maximumResponseTokens: maxTokens)
    }
    return GenerationOptions()
}

// MARK: - Model Manager

actor ModelManager {
    private let model: SystemLanguageModel

    init() {
        self.model = SystemLanguageModel.default
    }

    func checkAvailability() -> (available: Bool, reason: String?) {
        let availability = model.availability
        switch availability {
        case .available:
            return (true, nil)
        case .unavailable(let reason):
            let reasonString: String
            switch reason {
            case .deviceNotEligible:
                reasonString = "Device not eligible for Apple Intelligence. Requires Mac with Apple Silicon."
            case .appleIntelligenceNotEnabled:
                reasonString = "Apple Intelligence not enabled. Enable it in System Settings > Apple Intelligence & Siri."
            case .modelNotReady:
                reasonString = "AI model not ready. Models are downloaded automatically. Please wait and try again later."
            @unknown default:
                reasonString = "Unknown availability issue"
            }
            return (false, reasonString)
        @unknown default:
            return (false, "Unknown availability status")
        }
    }

    func generateResponse(messages: [JsonMessage], temperature: Double?, maxTokens: Int?) async throws -> String {
        let (available, reason) = checkAvailability()
        guard available else {
            throw NSError(domain: "AppleAI", code: 1, userInfo: [NSLocalizedDescriptionKey: reason ?? "Model unavailable"])
        }

        let (session, prompt) = try buildSession(from: messages)
        let options = buildOptions(temperature: temperature, maxTokens: maxTokens)
        let response = try await session.respond(to: prompt, options: options)
        return response.content
    }

    func streamResponse(messages: [JsonMessage], temperature: Double?, maxTokens: Int?) async throws {
        let (available, reason) = checkAvailability()
        guard available else {
            throw NSError(domain: "AppleAI", code: 1, userInfo: [NSLocalizedDescriptionKey: reason ?? "Model unavailable"])
        }

        let (session, prompt) = try buildSession(from: messages)
        let options = buildOptions(temperature: temperature, maxTokens: maxTokens)

        let responseStream = session.streamResponse(to: prompt, options: options)
        var previousByteCount = 0

        for try await cumulativeResponse in responseStream {
            let fullBytes = Array(cumulativeResponse.content.utf8)
            if fullBytes.count > previousByteCount {
                let deltaBytes = fullBytes[previousByteCount...]
                if let deltaContent = String(bytes: deltaBytes, encoding: .utf8) {
                    let delta = JsonResponse(type: "stream_delta", content: deltaContent, error: nil, modelAvailable: nil, reason: nil, finishReason: nil)
                    writeLine(delta)
                }
                previousByteCount = fullBytes.count
            }
        }

        let done = JsonResponse(type: "stream_done", content: nil, error: nil, modelAvailable: nil, reason: nil, finishReason: "stop")
        writeLine(done)
    }
}

// MARK: - I/O Helpers

let encoder = JSONEncoder()
let decoder = JSONDecoder()

func writeLine(_ response: JsonResponse) {
    guard let data = try? encoder.encode(response) else { return }
    guard let jsonString = String(data: data, encoding: .utf8) else { return }
    print(jsonString) // prints to stdout with newline
    fflush(stdout)
}

func writeError(_ message: String) {
    let response = JsonResponse(type: "error", content: nil, error: message, modelAvailable: nil, reason: nil, finishReason: nil)
    writeLine(response)
}

// MARK: - Main Loop

let manager = ModelManager()

// Signal readiness
writeLine(JsonResponse(type: "ready", content: nil, error: nil, modelAvailable: nil, reason: nil, finishReason: nil))

while let line = readLine() {
    guard !line.isEmpty else { continue }
    guard let data = line.data(using: .utf8) else {
        writeError("Invalid UTF-8 input")
        continue
    }

    let request: JsonRequest
    do {
        request = try decoder.decode(JsonRequest.self, from: data)
    } catch {
        writeError("Invalid JSON: \(error.localizedDescription)")
        continue
    }

    switch request.action {
    case "status":
        let (available, reason) = await manager.checkAvailability()
        writeLine(JsonResponse(type: "status", content: nil, error: nil, modelAvailable: available, reason: reason, finishReason: nil))

    case "generate":
        do {
            let result = try await manager.generateResponse(
                messages: request.messages ?? [],
                temperature: request.temperature,
                maxTokens: request.maxTokens
            )
            writeLine(JsonResponse(type: "result", content: result, error: nil, modelAvailable: nil, reason: nil, finishReason: "stop"))
        } catch {
            writeError(error.localizedDescription)
        }

    case "stream":
        do {
            try await manager.streamResponse(
                messages: request.messages ?? [],
                temperature: request.temperature,
                maxTokens: request.maxTokens
            )
        } catch {
            writeError(error.localizedDescription)
        }

    default:
        writeError("Unknown action: \(request.action)")
    }
}
