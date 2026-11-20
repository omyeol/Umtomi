import SwiftUI

@MainActor
struct UmtomiLockGate: View {
    @ObservedObject var store: AppStore
    @EnvironmentObject private var lock: AppLock
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if lock.locked {
                AppUnlockView()
            } else {
                RootView(store: store)
            }
        }
        .overlay {
            if !lock.locked && store.settings.hideAppInSwitcher
                && (phase == .background || (phase == .inactive && !lock.suppressInactiveShield)) {
                AppSwitcherShieldView()
            }
        }
        .onChange(of: phase, initial: true) { _, next in lock.phaseChanged(next) }
    }
}

@MainActor
private struct AppUnlockView: View {
    @EnvironmentObject private var lock: AppLock
    @Environment(\.scenePhase) private var phase
    @Environment(\.colorScheme) private var colorScheme
    @State private var password = ""
    @State private var legacyAuthorized = false
    @State private var triedBiometrics = false

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill").font(.largeTitle)
            Text("Umtomi is locked").font(.title3.bold())

            if lock.requiresPasswordSetup {
                if legacyAuthorized {
                    AppLockPasswordForm(action: .migrate)
                } else {
                    Button("Set up app password") {
                        Task { legacyAuthorized = await lock.authenticateLegacy() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(lock.authenticating)
                    Text("Confirm your iPhone authentication to set an app password.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                SecureField("App password", text: $password)
                    .textContentType(.password)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.go)
                    .onSubmit(unlock)
                Button("Unlock", action: unlock)
                    .buttonStyle(.borderedProminent)
                    .disabled(password.isEmpty || lock.authenticating)
                if lock.biometrics {
                    Button("Use biometrics") {
                        Task(priority: .userInteractive) { await lock.unlockWithBiometrics() }
                    }
                    .disabled(lock.authenticating)
                }
            }
            if lock.authenticating { ProgressView() }
            if let message = lock.message, !legacyAuthorized {
                Text(message).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colorScheme == .dark ? Color.black : Color(uiColor: .systemBackground))
        .onAppear {
            lock.prepareBiometrics()
            triggerBiometricsIfActive(phase)
        }
        .onChange(of: phase) { _, next in
            if next == .background {
                password = ""
                legacyAuthorized = false
                triedBiometrics = false
            } else if next == .active {
                triggerBiometricsIfActive(next)
            } else {
                lock.prepareBiometrics()
            }
        }
    }

    private func triggerBiometricsIfActive(_ currentPhase: ScenePhase) {
        guard currentPhase == .active, lock.biometrics, !lock.requiresPasswordSetup, !triedBiometrics else { return }
        triedBiometrics = true
        // Run outside .task(id: phase) so Face ID temporarily making the scene inactive does not cancel it.
        Task(priority: .userInteractive) { await lock.unlockWithBiometrics() }
    }

    private func unlock() {
        guard !password.isEmpty, !lock.authenticating else { return }
        let entered = password
        password = ""
        Task { await lock.unlockWithPassword(entered) }
    }
}

@MainActor
struct AppLockSettingsView: View {
    @EnvironmentObject private var lock: AppLock
    @Environment(\.colorScheme) private var colorScheme
    @State private var passwordAction: AppLockPasswordForm.Action?
    @State private var minutes = ""
    @State private var invalidMinutes = false

    var body: some View {
        Form {
            Section {
                Toggle("Enable app lock", isOn: Binding(get: { lock.enabled }, set: {
                    passwordAction = $0 ? .enable : .disable
                }))
                .disabled(lock.authenticating)
                if lock.enabled {
                    Button("Change app password") { passwordAction = .change }
                        .disabled(lock.authenticating)
                }
            }

            if lock.enabled {
                Section {
                    Toggle("Use biometrics", isOn: Binding(get: { lock.biometrics }, set: { enabled in
                        Task { await lock.setBiometrics(enabled) }
                    }))
                    .disabled(lock.authenticating)
                } footer: {
                    Text("When enabled, Face ID or Touch ID is offered first. You can always enter the app password.")
                }

                Section {
                    Toggle("Lock immediately", isOn: Binding(get: { lock.immediate }, set: {
                        lock.setImmediate($0)
                    }))
                    HStack {
                        Text("Lock after (minutes)")
                        Spacer()
                        TextField("Minutes", text: $minutes)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .monospacedDigit()
                            .accessibilityLabel("Lock after (minutes)")
                            .onChange(of: minutes) { _, value in
                                guard let number = Int(value), (1...1440).contains(number) else {
                                    invalidMinutes = true
                                    return
                                }
                                invalidMinutes = false
                                lock.setMinutes(number)
                            }
                    }
                    .disabled(lock.immediate)
                    .opacity(lock.immediate ? 0.42 : 1)
                    Button("Lock now") { lock.lockNow() }
                } footer: {
                    if invalidMinutes && !lock.immediate {
                        Text("Enter a number from 1 to 1440.").foregroundStyle(.red)
                    } else {
                        Text("The timer starts when you leave the app.")
                    }
                }
                if let message = lock.message, passwordAction == nil {
                    Section { Text(message).foregroundStyle(.secondary) }
                }
            }
        }
        .scrollContentBackground(colorScheme == .dark ? .hidden : .automatic)
        .background(colorScheme == .dark ? Color.black : Color.clear)
        .navigationTitle("App lock")
        .onAppear { minutes = String(lock.minutes) }
        .sheet(item: $passwordAction) { action in
            NavigationStack {
                Form { AppLockPasswordForm(action: action) }
                    .scrollContentBackground(colorScheme == .dark ? .hidden : .automatic)
                    .background(colorScheme == .dark ? Color.black : Color.clear)
                    .navigationTitle(action.title)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { passwordAction = nil }
                                .disabled(lock.authenticating)
                        }
                    }
            }
            .interactiveDismissDisabled(lock.authenticating)
        }
    }
}

@MainActor
private struct AppLockPasswordForm: View {
    enum Action: String, Identifiable {
        case enable, change, disable, migrate

        var id: Self { self }
        var title: LocalizedStringKey {
            switch self {
            case .enable, .migrate: return "Set up app password"
            case .change: return "Change app password"
            case .disable: return "Disable app lock"
            }
        }
    }

    @EnvironmentObject private var lock: AppLock
    @Environment(\.dismiss) private var dismiss
    let action: Action
    @State private var current = ""
    @State private var newPassword = ""
    @State private var confirmation = ""

    private var valid: Bool {
        switch action {
        case .disable: return !current.isEmpty
        case .change: return !current.isEmpty && validNewPassword
        case .enable, .migrate: return validNewPassword
        }
    }

    private var validNewPassword: Bool {
        newPassword.count >= 4 && newPassword.utf8.count <= 256 && newPassword == confirmation
    }

    var body: some View {
        if action == .change || action == .disable {
            SecureField("Current app password", text: $current)
                .textContentType(.password)
        }
        if action != .disable {
            SecureField("New app password", text: $newPassword)
                .textContentType(.newPassword)
            SecureField("Confirm app password", text: $confirmation)
                .textContentType(.newPassword)
            Text("Use at least four characters.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        Button(action == .disable ? "Disable app lock" : "Save") {
            Task {
                let saved: Bool
                if action == .disable {
                    saved = await lock.disable(password: current)
                } else {
                    saved = await lock.savePassword(newPassword, current: current, legacyAuthorized: action == .migrate)
                }
                if saved, action != .migrate { dismiss() }
            }
        }
        .disabled(!valid || lock.authenticating)
        if let message = lock.message { Text(message).foregroundStyle(.secondary) }
    }
}
