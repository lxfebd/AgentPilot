import SwiftUI

/// Create form for a schedule.
///
/// Validation and request shaping come from `SchedulePresentation`, so the same
/// rules the Web and Desktop clients enforce apply here. The Server is still
/// the final authority.
struct ScheduleCreateForm: View {
    @Binding var draft: ScheduleDraft
    let labels: ScheduleValidationLabels
    let sessions: [V2SessionMeta]
    let onSubmit: (V2ScheduleCreateRequest) async -> Void
    let onCancel: () -> Void

    @State private var isSubmitting = false
    @State private var submitError: String?

    private var result: ScheduleValidationResult {
        validateScheduleDraft(draft, labels: labels)
    }

    var body: some View {
        Form {
            Section(String(localized: "Task")) {
                TextField(String(localized: "Task name"), text: $draft.title)
                if let error = result.fieldErrors.title {
                    FieldError(text: error)
                }
            }

            Section(String(localized: "Message")) {
                TextField(
                    String(localized: "Message to send when it fires"),
                    text: $draft.prompt,
                    axis: .vertical
                )
                .lineLimit(3...8)
                if let error = result.fieldErrors.prompt {
                    FieldError(text: error)
                }
            }

            Section(String(localized: "Trigger")) {
                Picker(String(localized: "Kind"), selection: $draft.scheduleKind) {
                    Text(String(localized: "Once")).tag(V2ScheduleKind.once)
                    Text(String(localized: "Every day")).tag(V2ScheduleKind.daily)
                    Text(String(localized: "Every week")).tag(V2ScheduleKind.weekly)
                }
                .pickerStyle(.segmented)

                if draft.scheduleKind == .once {
                    DatePicker(
                        String(localized: "Run at"),
                        selection: runAtBinding(),
                        displayedComponents: [.date, .hourAndMinute]
                    )
                    if let error = result.fieldErrors.runAt {
                        FieldError(text: error)
                    }
                    Text(String(localized: "Interpreted in \(draft.timezone). Must be in the future."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    TextField(String(localized: "Time (HH:MM)"), text: runTimeText)
                        .keyboardType(.numbersAndPunctuation)
                    if let error = result.fieldErrors.runTime {
                        FieldError(text: error)
                    }
                }

                if draft.scheduleKind == .weekly {
                    Picker(String(localized: "Weekday"), selection: weekdayBinding()) {
                        ForEach(0..<7, id: \.self) { index in
                            Text(SchedulePresentation.weekdaySymbol(index) ?? "\(index)").tag(index)
                        }
                    }
                    if let error = result.fieldErrors.weekday {
                        FieldError(text: error)
                    }
                }

                TextField(String(localized: "Timezone"), text: $draft.timezone)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                if let error = result.fieldErrors.timezone {
                    FieldError(text: error)
                }
                Text(String(localized: "IANA name, for example Asia/Shanghai."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section(String(localized: "Target")) {
                Picker(String(localized: "Target"), selection: $draft.target.mode) {
                    Text(String(localized: "Existing session")).tag(ScheduleTargetMode.session)
                    Text(String(localized: "New session")).tag(ScheduleTargetMode.project)
                }
                .pickerStyle(.segmented)

                if draft.target.mode == .session {
                    if sessions.isEmpty {
                        Text(String(localized: "No sessions loaded. A new-session target can be used instead."))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Picker(String(localized: "Session"), selection: sessionBinding()) {
                            Text(String(localized: "Choose a session")).tag("")
                            ForEach(sessions, id: \.id) { session in
                                Text(session.title ?? String(localized: "Untitled session")).tag(session.id)
                            }
                        }
                    }
                } else {
                    TextField(String(localized: "Project ID"), text: projectIdText)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField(String(localized: "Connector ID"), text: connectorIdText)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                if let error = result.fieldErrors.target {
                    FieldError(text: error)
                }
            }

            if let submitError {
                Section {
                    Label(submitError, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }
        }
        .navigationTitle(String(localized: "New task"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "Cancel"), action: onCancel)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    submit()
                } label: {
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Text(String(localized: "Create"))
                    }
                }
                .disabled(!result.ok || isSubmitting)
            }
        }
    }

    // MARK: - Bindings

    private var runTimeText: Binding<String> {
        Binding(
            get: { draft.runTime ?? "" },
            set: { draft.runTime = $0.isEmpty ? nil : $0 }
        )
    }

    private var projectIdText: Binding<String> {
        Binding(
            get: { draft.target.projectId ?? "" },
            set: { draft.target.projectId = $0.isEmpty ? nil : $0 }
        )
    }

    private var connectorIdText: Binding<String> {
        Binding(
            get: { draft.target.connectorId ?? "" },
            set: { draft.target.connectorId = $0.isEmpty ? nil : $0 }
        )
    }

    private func sessionBinding() -> Binding<String> {
        Binding(
            get: { draft.target.sessionId ?? "" },
            set: { draft.target.sessionId = $0.isEmpty ? nil : $0 }
        )
    }

    private func weekdayBinding() -> Binding<Int> {
        Binding(
            get: { draft.weekday ?? 0 },
            set: { draft.weekday = $0 }
        )
    }

    /// `DatePicker` works in instants; the draft stores a UTC ISO-8601 string.
    private func runAtBinding() -> Binding<Date> {
        Binding(
            get: {
                SchedulePresentation.parseUTInstant(draft.runAt) ?? Date().addingTimeInterval(3600)
            },
            set: { draft.runAt = $0.ISO8601Format() }
        )
    }

    private func submit() {
        guard result.ok, !isSubmitting else { return }
        isSubmitting = true
        submitError = nil
        let request = buildScheduleCreateRequest(draft)
        Task {
            await onSubmit(request)
            isSubmitting = false
        }
    }
}

private struct FieldError: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.red)
    }
}
