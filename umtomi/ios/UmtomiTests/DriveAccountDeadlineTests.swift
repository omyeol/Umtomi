import XCTest
@testable import Umtomi

@MainActor
final class DriveAccountDeadlineTests: XCTestCase {
    func testTimeoutReturnsBeforeLateCallback() async {
        var callback: CheckedContinuation<Int, Never>?
        defer { callback?.resume(returning: 42) }

        do {
            _ = try await DriveAccountDeadline<Int>.wait(seconds: 1, stage: "test login") {
                await withCheckedContinuation { callback = $0 }
            }
            XCTFail("Expected the login deadline to expire")
        } catch DriveSyncFailure.timeout(let stage) {
            XCTAssertEqual(stage, "test login")
            XCTAssertNotNil(callback)
        } catch {
            XCTFail("Unexpected failure: \(error)")
        }
    }

    func testCallerCancellationReturnsBeforeLateCallback() async {
        var callback: CheckedContinuation<Int, Never>?
        let started = AsyncStream<Bool>.makeStream()
        let task = Task {
            try await DriveAccountDeadline<Int>.wait(stage: "test login") {
                await withCheckedContinuation {
                    callback = $0
                    started.continuation.yield(true)
                    started.continuation.finish()
                }
            }
        }
        for await _ in started.stream { break }
        task.cancel()
        defer { callback?.resume(returning: 42) }
        do {
            _ = try await task.value
            XCTFail("Expected cancellation")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }
}
