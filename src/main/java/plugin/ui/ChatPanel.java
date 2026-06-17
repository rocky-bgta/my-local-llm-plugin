package plugin.ui;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import plugin.llm.LMStudioClient;
import plugin.llm.model.ChatMessage;
import plugin.settings.PluginSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ChatPanel {

    private final JPanel root;

    // Settings widgets
    private JTextField        endpointField;
    private JComboBox<String> modelCombo;
    private JButton           refreshBtn;
    private JLabel            statusLabel;

    // Chat widgets
    private JTextArea  chatArea;
    private JTextField promptField;
    private JButton    sendBtn;
    private JProgressBar spinner;

    private final List<ChatMessage> history = new ArrayList<>();

    public ChatPanel(@NotNull Project project) {
        root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));

        root.add(buildSettingsPanel(), BorderLayout.NORTH);
        root.add(buildChatArea(),      BorderLayout.CENTER);
        root.add(buildInputPanel(),    BorderLayout.SOUTH);

        loadSettings();
    }

    // -------------------------------------------------------------------------
    // Panel builders
    // -------------------------------------------------------------------------

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Settings", TitledBorder.LEFT, TitledBorder.TOP));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor  = GridBagConstraints.WEST;
        lc.insets  = new Insets(3, 6, 3, 4);

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill      = GridBagConstraints.HORIZONTAL;
        fc.weightx   = 1.0;
        fc.gridwidth = GridBagConstraints.REMAINDER;
        fc.insets    = new Insets(3, 0, 3, 6);

        endpointField = new JTextField("http://127.0.0.1:1234");
        modelCombo    = new JComboBox<>();
        refreshBtn    = new JButton("Refresh Models");
        JButton saveBtn = new JButton("Save Settings");
        statusLabel   = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        // Row 0 — endpoint
        addRow(panel, "Endpoint:", endpointField, lc, fc, 0);
        // Row 1 — refresh button (full width)
        fc.gridy = 1;
        panel.add(refreshBtn, fc);
        // Row 2 — model
        addRow(panel, "Model:", modelCombo, lc, fc, 2);
        // Row 3 — save button (full width)
        fc.gridy = 3;
        panel.add(saveBtn, fc);
        // Row 4 — status
        fc.gridy = 4;
        panel.add(statusLabel, fc);

        refreshBtn.addActionListener(e -> refreshModels());
        saveBtn.addActionListener(e -> saveSettings());

        return panel;
    }

    private static void addRow(JPanel panel, String labelText, JComponent field,
                               GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridx = 0; lc.gridy = row;
        panel.add(new JLabel(labelText), lc);
        fc.gridx = 1; fc.gridy = row;
        panel.add(field, fc);
    }

    private JScrollPane buildChatArea() {
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        chatArea.setMargin(new Insets(4, 6, 4, 6));

        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        return scroll;
    }

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

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
    // Settings
    // -------------------------------------------------------------------------

    private void loadSettings() {
        PluginSettings s = PluginSettings.getInstance();
        endpointField.setText(s.getEndpoint());
        if (s.getModel() != null && !s.getModel().isBlank()) {
            modelCombo.addItem(s.getModel());
            modelCombo.setSelectedItem(s.getModel());
        }
    }

    private void saveSettings() {
        PluginSettings s = PluginSettings.getInstance();
        s.setEndpoint(endpointField.getText().trim());
        Object sel = modelCombo.getSelectedItem();
        if (sel != null) s.setModel(sel.toString());
        setStatus("Settings saved.");
    }

    // -------------------------------------------------------------------------
    // API calls — network on daemon thread, UI update on EDT
    // -------------------------------------------------------------------------

    private void refreshModels() {
        String endpoint = endpointField.getText().trim();
        setLoading(true);
        setStatus("Fetching models…");

        daemon(() -> {
            try {
                List<String> models = new LMStudioClient(endpoint).fetchModels();
                SwingUtilities.invokeLater(() -> {
                    modelCombo.removeAllItems();
                    models.forEach(modelCombo::addItem);
                    String saved = PluginSettings.getInstance().getModel();
                    if (models.contains(saved)) modelCombo.setSelectedItem(saved);
                    setStatus("Loaded " + models.size() + " model(s).");
                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Error: " + ex.getMessage());
                    setLoading(false);
                });
            }
        });
    }

    private void sendMessage() {
        String text = promptField.getText().trim();
        if (text.isEmpty()) return;

        Object sel = modelCombo.getSelectedItem();
        if (sel == null || sel.toString().isBlank()) {
            appendChat("System", "Please select a model first (open Settings → Refresh Models).");
            return;
        }

        String model    = sel.toString();
        String endpoint = endpointField.getText().trim();

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
    // UI helpers — call only on EDT
    // -------------------------------------------------------------------------

    private void appendChat(String role, String content) {
        chatArea.append(role + ":\n" + content + "\n\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void setLoading(boolean loading) {
        sendBtn.setEnabled(!loading);
        refreshBtn.setEnabled(!loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Public API for ChatToolWindowFactory
    // -------------------------------------------------------------------------

    public JPanel getSwingComponent() {
        return root;
    }
}
