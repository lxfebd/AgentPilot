import SwiftUI

struct AAWordmark: View {
    let fontSize: CGFloat

    var body: some View {
        Text(verbatim: "AgentPilot\u{2009}")
            .font(.system(fontSize, weight: .semibold))
            .tracking(-0.5)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: true)
            .frame(height: fontSize)
    }
}
