import SwiftUI

/// Schedules management: list, create, pause/resume and delete.
///
/// The pure rules (trigger summary, validation, request shaping) live in
/// `SchedulePresentation`, which is shared with the unit tests. This view only
/// maps them onto SwiftUI.
struct SchedulesView: View {
    let api: V2ScheduleAPI

    @State private var model: ScheduleListModel
    @State private var draft = ScheduleDraft(timezone: currentTimezoneIdentifier())
    @State private var showsCreate = false
    @State private var createErrorMessage: String?
    @Environment(\.dismiss) private var dismiss

    init(api: V2ScheduleAPI) {
        self.api = api
        _model = State(initialValue: ScheduleListModel(api: api))
    }

    private var labels: SchedulePresentation.TriggerSummaryLabels {
        SchedulePresentation.TriggerSummaryLabels(
            once: String(localized: "Once"),
            daily: String(localized: "Every day"),
            weekly: String(localized: "Every {weekday}"),
            weekdays: Calendar.current.standaloneWeekdaySymbols.reorderedFromMonday(),
            timezoneSuffix: { "($0)" }
        )
    }

    private var validationLabels: ScheduleValidationLabels {
        ScheduleValidationLabels(
            titleRequired: String(localized: "Enter a task name."),
            promptRequired: String(localized: "Enter the message to send."),
            targetRequired: String(localized: "Choose a target session."),
            runAtRequired: String(localized: "Choose when to run."),
            runAtFuture: String(localized: "The run time must be in the future."),
            runTimeRequired: String(localized: "Enter a time as HH:MM."),
            weekdayRequired: String(localized: "Choose a weekday."),
            timezoneInvalid: String(localized: "Unknown timezone.")
        )
    }

    var body: some View {
        List {
                    Section {
                        Label(loadErrorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
            ForEach(model.sortedSchedules) { schedule in
                ScheduleRow(
                    schedule: schedule,
                    labels: labels,
                    busy: model.busyScheduleId == schedule.id,
                    onToggle: { enabled in Task { await model.setEnabled(schedule, enabled: enabled) } },
                    onDelete: { model.deletingSchedule = schedule }
                )
            }
        }
        .overlay {
            if model.isLoading && model.schedules.isEmpty {
                ProgressView()
            } else if model.schedules.isEmpty && !model.isLoading {
                ContentUnavailableView(
                    String(localized: "No scheduled tasks yet."),
                    systemImage: "calendar.badge.plus",
                    description: Text(String(localized: "Create one to have the Server send a message on a schedule."))
                )
            }
        }
        .navigationTitle(String(localized: "Schedules"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showsCreate = true
                } label: {
                    Label(String(localized: "New task"), systemImage: "plus")
                }
            }
        }
        .sheet(isPresented: $showsCreate) {
            NavigationStack {
                ScheduleCreateForm(
                    draft: $draft,
                    labels: validationLabels,
                    sessions: [],
                    onSubmit: { request in
                        do {
                            try await model.create(request)
                            showsCreate = false
                            draft = ScheduleDraft(timezone: currentTimezoneIdentifier())
                            createErrorMessage = nil
                        } catch {
                            createErrorMessage = error.localizedDescription
                        }
                    },
                    onCancel: { showsCreate = false }
                )
            }
        }
        .alert(
            String(localized: "Delete schedule?"),
            isPresented: Binding(
                get: { model.deletingSchedule != nil },
                set: { if !$0 { model.deletingSchedule = nil } }
            ),
            presenting: model.deletingSchedule
        ) { schedule in
            Button(String(localized: "Delete"), role: .destructive) {
                Task { await model.delete(schedule) }
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        } message: { schedule in
            Text(String(localized: "“\(schedule.title)” will stop firing."))
        }
        .task { await model.load() }
        .refreshable { await model.load() }
    }
}

private struct ScheduleRow: View {
    let schedule: V2Schedule
    let labels: SchedulePresentation.TriggerSummaryLabels
    let busy: Bool
    let onToggle: (Bool) -> Void
    let onDelete: () -> Void

    private var finished: Bool { SchedulePresentation.hasFinished(schedule) }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if busy {
                ProgressView()
            } else {
                Image(systemName: finished ? "checkmark.circle" : "calendar.badge.clock")
                    .foregroundStyle(finished ? .secondary : .tint)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(schedule.title).font(.body.weight(.medium))
                Text(SchedulePresentation.describeTrigger(schedule, labels: labels))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(Text(String(localized: "Next run"))) \(SchedulePresentation.nextRunLabel(schedule, emptyLabel: String(localized: "not scheduled")))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if finished {
                    Text(String(localized: "Already ran"))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tint)
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 8) {
                Toggle(String(localized: "Enabled"), isOn: Binding(
                    get: { schedule.enabled },
                    set: { onToggle($0) }
                ))
                .labelsHidden()
                .disabled(busy || finished)
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .disabled(busy)
            }
        }
        .padding(.vertical, 4)
    }
}

/// Reorder the Gregorian weekday symbols (Sunday first) to the Server's order.
private extension Array where Element == String {
    func reorderedFromMonday() -> [String] {
        guard count == 7 else { return self }
        return Array(self[1...]) + [self[0]]
    }
}
