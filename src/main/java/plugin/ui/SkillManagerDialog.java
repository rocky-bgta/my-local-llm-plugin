package plugin.ui;

import plugin.memory.SkillMemory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

final class SkillManagerDialog {

    private SkillManagerDialog() {}

    static void show(Window parent, SkillMemory memory) {
        JDialog dialog = new JDialog(parent, "Skills", Dialog.ModalityType.APPLICATION_MODAL);
        SkillTableModel model = new SkillTableModel(memory);
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);

        JButton addBtn = new JButton("Add");
        JButton editBtn = new JButton("Edit");
        JButton toggleBtn = new JButton("Disable");
        JButton deleteBtn = new JButton("Delete");
        JButton refreshBtn = new JButton("Refresh");
        JButton closeBtn = new JButton("Close");

        Runnable refreshAction = model::refresh;

        addBtn.addActionListener(e -> {
            SkillEditorDialog.SkillEditResult result = SkillEditorDialog.edit(dialog, null, null, true);
            if (result != null) {
                memory.remember(result.name(), result.description());
                memory.setEnabled(result.name(), result.enabled());
                refreshAction.run();
            }
        });

        editBtn.addActionListener(e -> {
            SkillMemory.SkillEntry entry = selectedEntry(table, model);
            if (entry == null) return;
            SkillEditorDialog.SkillEditResult result = SkillEditorDialog.edit(
                    dialog, entry.name(), entry.description(), entry.enabled());
            if (result != null) {
                memory.update(entry.name(), result.name(), result.description(), result.enabled());
                refreshAction.run();
            }
        });

        toggleBtn.addActionListener(e -> {
            SkillMemory.SkillEntry entry = selectedEntry(table, model);
            if (entry == null) return;
            memory.setEnabled(entry.name(), !entry.enabled());
            refreshAction.run();
        });

        deleteBtn.addActionListener(e -> {
            SkillMemory.SkillEntry entry = selectedEntry(table, model);
            if (entry == null) return;
            if (JOptionPane.showConfirmDialog(dialog,
                    "Delete skill \"" + entry.name() + "\"?",
                    "Delete Skill",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                memory.delete(entry.name());
                refreshAction.run();
            }
        });

        refreshBtn.addActionListener(e -> refreshAction.run());
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(addBtn);
        actions.add(editBtn);
        actions.add(toggleBtn);
        actions.add(deleteBtn);
        actions.add(refreshBtn);
        actions.add(closeBtn);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setSize(new Dimension(900, 500));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static SkillMemory.SkillEntry selectedEntry(JTable table, SkillTableModel model) {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.getEntryAt(table.convertRowIndexToModel(row));
    }

    private static final class SkillTableModel extends AbstractTableModel {
        private final SkillMemory memory;
        private List<SkillMemory.SkillEntry> entries;

        private SkillTableModel(SkillMemory memory) {
            this.memory = memory;
            this.entries = memory.listAll();
        }

        void refresh() {
            entries = memory.listAll();
            fireTableDataChanged();
        }

        SkillMemory.SkillEntry getEntryAt(int row) {
            if (row < 0 || row >= entries.size()) return null;
            return entries.get(row);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Enabled";
                case 1 -> "Name";
                case 2 -> "Updated";
                case 3 -> "Description";
                default -> super.getColumnName(column);
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SkillMemory.SkillEntry entry = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> entry.enabled();
                case 1 -> entry.name();
                case 2 -> entry.updatedAt();
                case 3 -> entry.description();
                default -> "";
            };
        }
    }
}

final class SkillEditorDialog {

    private SkillEditorDialog() {}

    static SkillEditResult edit(Window parent, String originalName, String description, boolean enabled) {
        JTextField nameField = new JTextField(originalName == null ? "" : originalName, 32);
        JTextArea descriptionArea = new JTextArea(description == null ? "" : description, 8, 36);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        JCheckBox enabledBox = new JCheckBox("Enabled", enabled);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 8);
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.gridwidth = GridBagConstraints.REMAINDER;
        fc.insets = new Insets(4, 0, 4, 0);

        addRow(fields, "Name:", nameField, lc, fc, 0);
        addRow(fields, "Description:", new JScrollPane(descriptionArea), lc, fc, 1);
        fc.gridy = 2;
        fields.add(enabledBox, fc);
        panel.add(fields, BorderLayout.CENTER);

        int choice = JOptionPane.showConfirmDialog(parent, panel,
                originalName == null ? "Add Skill" : "Edit Skill",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return null;
        return new SkillEditResult(originalName, nameField.getText().trim(), descriptionArea.getText().trim(), enabledBox.isSelected());
    }

    private static void addRow(JPanel p, String label, JComponent field,
                               GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridx = 0;
        lc.gridy = row;
        p.add(new JLabel(label), lc);
        fc.gridx = 1;
        fc.gridy = row;
        p.add(field, fc);
    }

    record SkillEditResult(String originalName, String name, String description, boolean enabled) {}
}
