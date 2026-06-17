package plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import plugin.llm.LMStudioClient;
import plugin.llm.model.ChatMessage;
import plugin.settings.PluginSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ChatPanel {

    private final JPanel root;

    private JTextArea    chatArea;
    private JTextField   promptField;
    private JButton      sendBtn;
    private JProgressBar spinner;

    private final List<ChatMessage> history = new ArrayList<>();

    public ChatPanel(@NotNull Project project) {
        root = new JPanel(new BorderLayout(0, 0));
        root.add(buildToolbar(),    BorderLayout.NORTH);
        root.add(buildChatArea(),   BorderLayout.CENTER);
        root.add(buildInputPanel(), BorderLayout.SOUTH);
    }

    // -------------------------------------------------------------------------
    // Toolbar with gear icon
    // -------------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0,
                UIManager.getColor("Separator.foreground")));

        JButton gearBtn = new JButton(AllIcons.General.Settings);
        gearBtn.setBorderPainted(false);
        gearBtn.setContentAreaFilled(false);
        gearBtn.setFocusPainted(false);
        gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearBtn.setToolTipText("Settings");
        gearBtn.addActionListener(e -> showSettingsDialog());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        right.setOpaque(false);
        right.add(gearBtn);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // -------------------------------------------------------------------------
    // Settings dialog (opens on gear click)
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        Window parent = SwingUtilities.getWindowAncestor(root);
        JDialog dialog = new JDialog(parent, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());
        dialog.add(buildSettingsForm(dialog), BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(380, dialog.getHeight()));
        dialog.setLocationRelativeTo(root);
        dialog.setResizable(false);
        dialog.setVisible(true);
    }

    private JPanel buildSettingsForm(JDialog dialog) {
        PluginSettings settings = PluginSettings.getInstance();

        JTextField        endpointField = new JTextField(settings.getEndpoint(), 28);
        JComboBox<String> modelCombo    = new JComboBox<>();
        JLabel            statusLabel   = new JLabel(" ");
        JButton           refreshBtn    = new JButton("Refresh Models");
        JButton           saveBtn       = new JButton("Save");

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        if (settings.getModel() != null && !settings.getModel().isBlank()) {
            modelCombo.addItem(settings.getModel());
            modelCombo.setSelectedItem(settings.getModel());
        }

        refreshBtn.addActionListener(e -> {
            String ep = endpointField.getText().trim();
            refreshBtn.setEnabled(false);
            statusLabel.setText("Fetching models…");
            daemon(() -> {
                try {
                    List<String> models = new LMStudioClient(ep).fetchModels();
                    SwingUtilities.invokeLater(() -> {
                        modelCombo.removeAllItems();
                        models.forEach(modelCombo::addItem);
                        String saved = settings.getModel();
                        if (saved != null && models.contains(saved)) {
                            modelCombo.setSelectedItem(saved);
                        }
                        statusLabel.setText("Loaded " + models.size() + " model(s).");
                        refreshBtn.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Error: " + ex.getMessage());
                        refreshBtn.setEnabled(true);
                    });
                }
            });
        });

        saveBtn.addActionListener(e -> {
            settings.setEndpoint(endpointField.getText().trim());
            Object sel = modelCombo.getSelectedItem();
            if (sel != null && !sel.toString().isBlank()) {
                settings.setModel(sel.toString());
            }
            dialog.dispose();
        });

        // Layout
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(14, 18, 14, 18));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(5, 0, 5, 10);

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill      = GridBagConstraints.HORIZONTAL;
        fc.weightx   = 1.0;
        fc.gridwidth = GridBagConstraints.REMAINDER;
        fc.insets    = new Insets(5, 0, 5, 0);

        addFormRow(form, "Endpoint:", endpointField, lc, fc, 0);
        fc.gridy = 1; form.add(refreshBtn,  fc);
        addFormRow(form, "Model:",    modelCombo,    lc, fc, 2);
        fc.gridy = 3; form.add(saveBtn,     fc);
        fc.gridy = 4; form.add(statusLabel, fc);

        return form;
    }

    private static void addFormRow(JPanel panel, String labelText, JComponent field,
                                   GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridx = 0; lc.gridy = row;
        panel.add(new JLabel(labelText), lc);
        fc.gridx = 1; fc.gridy = row;
        panel.add(field, fc);
    }

    // -------------------------------------------------------------------------
    // Chat area
    // -------------------------------------------------------------------------

    private JScrollPane buildChatArea() {
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        chatArea.setMargin(new Insets(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        return scroll;
    }

    // -------------------------------------------------------------------------
    // Input row (prompt + Send / Clear + spinner)
    // -------------------------------------------------------------------------

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(6, 8, 8, 8));

        promptField = new JTextField();
        promptField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        promptField.addActionListener(e -> sendMessage());

        sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendMessage());

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            chatArea.setText("");
            history.clear();
        });

        spinner = new JProgressBar();
        spinner.setIndeterminate(false);
        spinner.setPreferredSize(new Dimension(80, 14));
        spinner.setVisible(false);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(promptField, BorderLayout.CENTER);
        inputRow.add(sendBtn,     BorderLayout.EAST);

        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ctrlRow.add(clearBtn);
        ctrlRow.add(spinner);

        panel.add(inputRow, BorderLayout.CENTER);
        panel.add(ctrlRow,  BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // API calls — network on daemon thread, UI updates on EDT
    // -------------------------------------------------------------------------

    private void sendMessage() {
        String text = promptField.getText().trim();
        if (text.isEmpty()) return;

        PluginSettings s    = PluginSettings.getInstance();
        String model        = s.getModel();
        String endpoint     = s.getEndpoint();

        if (model == null || model.isBlank()) {
            appendChat("System", "No model configured — click ⚙ to open Settings.");
            return;
        }

        appendChat("You", text);
        history.add(new ChatMessage("user", text));
        promptField.setText("");
        setLoading(true);

        List<ChatMessage> snapshot = new ArrayList<>(history);
        daemon(() -> {
            try {
                String reply = new LMStudioClient(endpoint).chat(model, snapshot);
                SwingUtilities.invokeLater(() -> {
                    history.add(new ChatMessage("assistant", reply));
                    appendChat("Assistant", reply);
                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Error", ex.getMessage());
                    setLoading(false);
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void appendChat(String role, String content) {
        chatArea.append(role + ":\n" + content + "\n\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void setLoading(boolean loading) {
        sendBtn.setEnabled(!loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    public JPanel getSwingComponent() {
        return root;
    }
}
