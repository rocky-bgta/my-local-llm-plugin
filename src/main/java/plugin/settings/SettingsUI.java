package plugin.settings;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import plugin.llm.LMStudioClient;
import plugin.llm.LLMClient;
import plugin.llm.OllamaClient;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class SettingsUI implements Configurable {

    private JPanel rootPanel;
    private JRadioButton ollamaRadio;
    private JRadioButton lmStudioRadio;
    private JLabel urlLabel;
    private JTextField urlField;
    private JButton testButton;
    private JLabel statusLabel;
    private JComboBox<String> modelCombo;
    private JTextArea systemPromptArea;
    private JSlider tempSlider;
    private JLabel tempValueLabel;
    private JSpinner maxTokensSpinner;
    private JCheckBox autoApplyCheckBox;

    @Override
    public @Nls String getDisplayName() {
        return "Local LLM Assistant";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootPanel = new JPanel(new GridBagLayout());
        rootPanel.setBackground(UIManager.getColor("Panel.background"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        int row = 0;

        // --- Backend selection ---
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 0;
        rootPanel.add(sectionLabel("Backend"), gbc);
        row++;

        ollamaRadio = new JRadioButton("Ollama");
        lmStudioRadio = new JRadioButton("LM Studio");
        ButtonGroup group = new ButtonGroup();
        group.add(ollamaRadio);
        group.add(lmStudioRadio);

        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        radioPanel.setOpaque(false);
        radioPanel.add(ollamaRadio);
        radioPanel.add(lmStudioRadio);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
        rootPanel.add(radioPanel, gbc);
        row++;

        // --- URL field ---
        urlLabel = new JLabel("Ollama URL:");
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        rootPanel.add(urlLabel, gbc);
        urlField = new JTextField(30);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootPanel.add(urlField, gbc);
        row++;

        // Update URL label on radio change
        ollamaRadio.addActionListener(e -> urlLabel.setText("Ollama URL:"));
        lmStudioRadio.addActionListener(e -> urlLabel.setText("LM Studio URL:"));

        // --- Test Connection ---
        testButton = new JButton("Test Connection");
        statusLabel = new JLabel("  ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));

        JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        testPanel.setOpaque(false);
        testPanel.add(testButton);
        testPanel.add(statusLabel);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        rootPanel.add(testPanel, gbc);
        row++;

        testButton.addActionListener(e -> testConnection());

        // --- Model selection ---
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        rootPanel.add(new JLabel("Model:"), gbc);
        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootPanel.add(modelCombo, gbc);
        row++;

        // --- System prompt ---
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 0;
        rootPanel.add(sectionLabel("System Prompt"), gbc);
        row++;

        systemPromptArea = new JTextArea(5, 40);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JScrollPane(systemPromptArea);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.3;
        rootPanel.add(promptScroll, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        row++;

        // --- Temperature ---
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        rootPanel.add(new JLabel("Temperature:"), gbc);
        tempSlider = new JSlider(0, 100, 70);
        tempValueLabel = new JLabel("0.70");
        tempSlider.addChangeListener(e -> {
            double val = tempSlider.getValue() / 100.0;
            tempValueLabel.setText(String.format("%.2f", val));
        });
        JPanel tempPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tempPanel.setOpaque(false);
        tempPanel.add(tempSlider);
        tempPanel.add(tempValueLabel);
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootPanel.add(tempPanel, gbc);
        row++;

        // --- Max tokens ---
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        rootPanel.add(new JLabel("Max Tokens:"), gbc);
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(4096, 100, 32000, 100));
        gbc.gridx = 1; gbc.weightx = 1.0;
        rootPanel.add(maxTokensSpinner, gbc);
        row++;

        // --- Auto-apply ---
        autoApplyCheckBox = new JCheckBox("Auto-apply edits (skip diff preview — use with caution)");
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        rootPanel.add(autoApplyCheckBox, gbc);
        row++;

        // Filler
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        rootPanel.add(new JPanel(), gbc);

        reset();
        return rootPanel;
    }

    private void testConnection() {
        testButton.setEnabled(false);
        statusLabel.setText("Testing...");
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));

        PluginSettings s = PluginSettings.getInstance();
        boolean useOllama = ollamaRadio.isSelected();
        String url = urlField.getText().trim();

        new Thread(() -> {
            LLMClient client = useOllama
                    ? new OllamaClient(url, "")
                    : new LMStudioClient(url, "", s.getMaxTokens(), s.getTemperature());
            boolean available = client.isAvailable();
            List<String> models = available ? client.listModels() : List.of();

            SwingUtilities.invokeLater(() -> {
                testButton.setEnabled(true);
                if (available) {
                    statusLabel.setText("✓ Connected");
                    statusLabel.setForeground(new Color(0, 160, 0));
                    modelCombo.removeAllItems();
                    for (String m : models) modelCombo.addItem(m);
                    if (!models.isEmpty()) modelCombo.setSelectedIndex(0);
                } else {
                    statusLabel.setText("✗ Not available");
                    statusLabel.setForeground(new Color(200, 0, 0));
                }
            });
        }, "test-connection").start();
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        return label;
    }

    @Override
    public boolean isModified() {
        PluginSettings s = PluginSettings.getInstance();
        PluginSettings.Backend backend = ollamaRadio.isSelected()
                ? PluginSettings.Backend.OLLAMA : PluginSettings.Backend.LM_STUDIO;
        if (backend != s.getSelectedBackend()) return true;
        if (!urlField.getText().trim().equals(
                backend == PluginSettings.Backend.OLLAMA
                        ? s.getOllamaBaseUrl() : s.getLmStudioBaseUrl())) return true;
        Object selectedModel = modelCombo.getSelectedItem();
        if (selectedModel != null && !selectedModel.toString().equals(s.getSelectedModel())) return true;
        if (!systemPromptArea.getText().equals(s.getSystemPrompt())) return true;
        if (tempSlider.getValue() != (int) (s.getTemperature() * 100)) return true;
        if ((int) maxTokensSpinner.getValue() != s.getMaxTokens()) return true;
        if (autoApplyCheckBox.isSelected() != s.isAutoApplyEdits()) return true;
        return false;
    }

    @Override
    public void apply() {
        PluginSettings s = PluginSettings.getInstance();
        PluginSettings.Backend backend = ollamaRadio.isSelected()
                ? PluginSettings.Backend.OLLAMA : PluginSettings.Backend.LM_STUDIO;
        s.setSelectedBackend(backend);
        String url = urlField.getText().trim();
        if (backend == PluginSettings.Backend.OLLAMA) {
            s.setOllamaBaseUrl(url);
        } else {
            s.setLmStudioBaseUrl(url);
        }
        Object selectedModel = modelCombo.getSelectedItem();
        s.setSelectedModel(selectedModel != null ? selectedModel.toString() : "");
        s.setSystemPrompt(systemPromptArea.getText());
        s.setTemperature(tempSlider.getValue() / 100.0);
        s.setMaxTokens((int) maxTokensSpinner.getValue());
        s.setAutoApplyEdits(autoApplyCheckBox.isSelected());
    }

    @Override
    public void reset() {
        PluginSettings s = PluginSettings.getInstance();
        if (s.getSelectedBackend() == PluginSettings.Backend.OLLAMA) {
            ollamaRadio.setSelected(true);
            urlField.setText(s.getOllamaBaseUrl());
            urlLabel.setText("Ollama URL:");
        } else {
            lmStudioRadio.setSelected(true);
            urlField.setText(s.getLmStudioBaseUrl());
            urlLabel.setText("LM Studio URL:");
        }
        modelCombo.removeAllItems();
        if (!s.getSelectedModel().isEmpty()) {
            modelCombo.addItem(s.getSelectedModel());
            modelCombo.setSelectedItem(s.getSelectedModel());
        }
        systemPromptArea.setText(s.getSystemPrompt());
        int sliderVal = (int) (s.getTemperature() * 100);
        tempSlider.setValue(sliderVal);
        tempValueLabel.setText(String.format("%.2f", s.getTemperature()));
        maxTokensSpinner.setValue(s.getMaxTokens());
        autoApplyCheckBox.setSelected(s.isAutoApplyEdits());
    }
}
