import Foundation

/// The Google SDK can complete via callback after the caller has already stopped waiting.
/// A task group would wait for that callback even after cancelling the losing task.
@MainActor
final class DriveAccountDeadline<Value> {
    private var continuation: CheckedContinuation<Value, Error>?
    private var worker: Task<Void, Never>?
    private var timer: Task<Void, Never>?

    static func wait(
        seconds: Int = 25,
        stage: String,
        _ operation: @escaping @MainActor () async throws -> Value
    ) async throws -> Value {
        let attempt = DriveAccountDeadline()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                attempt.continuation = continuation
                attempt.worker = Task {
                    do { attempt.finish(.success(try await operation())) }
                    catch { attempt.finish(.failure(error)) }
                }
                attempt.timer = Task {
                    do { try await Task.sleep(for: .seconds(seconds)) }
                    catch { return }
                    attempt.finish(.failure(DriveSyncFailure.timeout(stage)))
                }
            }
        } onCancel: {
            Task { @MainActor in attempt.finish(.failure(CancellationError())) }
        }
    }

    private func finish(_ result: Result<Value, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        timer?.cancel()
        timer = nil
        worker?.cancel()
        worker = nil
        continuation.resume(with: result)
    }
}
