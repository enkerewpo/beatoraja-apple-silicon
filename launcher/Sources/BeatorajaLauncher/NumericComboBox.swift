import SwiftUI
import AppKit

/// An editable combo box over an integer setting: common values on the dropdown, anything
/// else typed in directly.
///
/// SwiftUI has no editable picker — its Picker is a closed list and its TextField has no
/// list — so this wraps AppKit's NSComboBox, which has been exactly this control for
/// decades. Typed values are clamped to `range`, which mirrors the bounds of the matching
/// spinner in upstream's FXML.
struct NumericComboBox: NSViewRepresentable {

    @Binding var value: Int
    let options: [Int]
    let range: ClosedRange<Int>

    func makeNSView(context: Context) -> NSComboBox {
        let box = NSComboBox()
        box.usesDataSource = false
        box.addItems(withObjectValues: options.map(String.init))
        box.numberOfVisibleItems = options.count
        box.isEditable = true
        box.completes = true
        box.alignment = .right
        // A bezelled field would sit oddly among the borderless rows of a grouped form; the
        // dropdown arrow alone is enough to say the value is also pickable.
        box.isBordered = false
        box.drawsBackground = false
        box.isButtonBordered = false
        box.focusRingType = .none
        box.font = .monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .regular)
        box.delegate = context.coordinator
        return box
    }

    func updateNSView(_ box: NSComboBox, context: Context) {
        context.coordinator.parent = self
        let text = String(value)
        if box.stringValue != text { box.stringValue = text }
        box.isEnabled = context.environment.isEnabled
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, NSComboBoxDelegate {
        var parent: NumericComboBox

        init(_ parent: NumericComboBox) { self.parent = parent }

        /// Picking from the list: the field's text has not caught up yet, so read the item.
        func comboBoxSelectionDidChange(_ notification: Notification) {
            guard let box = notification.object as? NSComboBox else { return }
            let index = box.indexOfSelectedItem
            guard options(box).indices.contains(index) else { return }
            commit(options(box)[index])
        }

        /// Typing: take whatever digits ended up in the field.
        func controlTextDidEndEditing(_ notification: Notification) {
            guard let box = notification.object as? NSComboBox else { return }
            commit(box.stringValue)
            box.stringValue = String(parent.value)
        }

        private func options(_ box: NSComboBox) -> [String] {
            box.objectValues.compactMap { $0 as? String }
        }

        private func commit(_ text: String) {
            let digits = text.filter(\.isNumber)
            guard let parsed = Int(digits) else { return }
            parent.value = min(max(parsed, parent.range.lowerBound), parent.range.upperBound)
        }
    }
}
