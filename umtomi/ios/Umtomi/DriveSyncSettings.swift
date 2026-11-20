import SwiftUI
import UIKit

@MainActor
struct DriveSyncSettings: View {
    let store: AppStore
    @ObservedObject private var sync = DriveSyncCoordinator.shared
    @AppStorage(DriveSyncCoordinator.backgroundKey) private var background = false
    @AppStorage(DriveSyncCoordinator.onDemandKey) private var onDemand = false
    @AppStorage(DriveSyncCoordinator.enabledKey) private var enabled = false
    @AppStorage(DriveSyncNotifications.preferenceKey) private var progressNotifications = false
    @State private var requestingNotifications = false
    @State private var notificationPermissionRequired = false

    var body: some View {
        Form {
            Section("Google Drive") {
                if !sync.configured {
                    Text("iOS용 Google OAuth 클라이언트를 빌드에 설정하면 연결할 수 있습니다.")
                        .foregroundStyle(.secondary)
                } else if enabled {
                    Button("지금 동기화") { Task { await sync.sync(store: store) } }
                        .disabled(sync.busy)
                    if sync.busy { Button("동기화 중단", role: .cancel) { sync.cancel() } }
                    Button("계정 연결 해제", role: .destructive) { sync.disconnect() }
                        .disabled(sync.busy)
                } else {
                    Button("Google 계정 연결") {
                        Task {
                            await sync.connect()
                            if enabled { _ = await sync.sync(store: store) }
                        }
                    }
                    .disabled(sync.busy)
                }
                if let progress = sync.progress {
                    ProgressView(value: progress) {
                        Text("현재 단계")
                    } currentValueLabel: {
                        Text(progress.formatted(.percent.precision(.fractionLength(0))))
                            .monospacedDigit()
                    }
                }
                else if sync.busy { ProgressView() }
                if !sync.status.isEmpty { Text(sync.status).font(.footnote) }
                if let last = sync.lastSuccess {
                    LabeledContent("마지막 동기화") { Text(last, format: .dateTime.month().day().hour().minute()) }
                }
            }
            Section {
                Picker("책장 사진 저장 방식", selection: $onDemand) {
                    Text("로컬에 저장").tag(false)
                    Text("필요할 때 클라우드에서 불러오기").tag(true)
                }
            } footer: {
                Text("두 방식 모두 Google Drive와 동기화됩니다. 이미 저장된 사진은 방식 변경 시 유지됩니다.")
            }
            Section {
                Toggle("백그라운드에서 책장 동기화", isOn: $background)
                    .disabled(!enabled)
                    .onChange(of: background) { _, _ in sync.schedule() }
            } footer: {
                Text("앱이 닫혀 있어도 iOS가 허용한 시간에 동기화합니다. 네트워크와 백그라운드 앱 새로 고침 설정의 영향을 받습니다.")
            }
            Section {
                Toggle("동기화 진행 알림", isOn: Binding(
                    get: { progressNotifications },
                    set: { updateNotifications($0) }
                ))
                .disabled(requestingNotifications)
                if notificationPermissionRequired {
                    Button("iPhone 알림 설정 열기") {
                        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                        UIApplication.shared.open(url)
                    }
                }
            } footer: {
                Text("일반 동기화와 예약된 백그라운드 동기화의 진행률과 결과를 알림으로 표시합니다.")
            }
        }
        .navigationTitle("Google Drive 동기화")
    }

    private func updateNotifications(_ enabled: Bool) {
        guard enabled else {
            progressNotifications = false
            DriveSyncNotifications.shared.clear()
            return
        }
        requestingNotifications = true
        Task {
            let granted = await DriveSyncNotifications.shared.requestPermission()
            progressNotifications = granted
            notificationPermissionRequired = !granted
            requestingNotifications = false
        }
    }
}
