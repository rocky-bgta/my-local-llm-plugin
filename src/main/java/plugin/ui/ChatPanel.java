package plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import plugin.llm.LocalLLMClient;
import plugin.llm.AttachmentData;
import plugin.llm.model.ChatMessage;
import plugin.integrations.IntegrationAccessUtil;
import plugin.settings.PluginSettings;
import plugin.agent.AgentTask;
import plugin.agent.PlannerAgent;
import plugin.llm.PromptBuilder;
import plugin.rag.ContextCollector;
import plugin.rag.RetrievalResult;
import plugin.memory.SkillMemory;
import plugin.testing.AutoFixLoop;
import plugin.tool.GitTool;
import plugin.util.AttachmentUtil;
import plugin.util.BuildUtil;
import plugin.util.FileOperationUtil;
import plugin.util.GitUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatPanel implements com.intellij.openapi.Disposable {

    // Lets actions (e.g. GenerateTestAction) reach the live panel for this project.
    public static final Key<ChatPanel> PANEL_KEY = Key.create("LocalLLM.ChatPanel");

    private final Project project;
    final JPanel root;

    // When set, buildRagContext uses this exact class as the retrieval target
    // instead of guessing from the query text. Cleared after one use.
    private String forcedTargetSymbol = null;

    // Mode
    private String mode = "PLANNING";
    private JComboBox<String> modeCombo;

    // Dynamic tab/toolbar title
    private JLabel  titleLabel;
    private JLabel  workspaceStatusLabel;
    private JLabel  activityLabel;
    private JLabel  fileHistoryLabel;
    private JLabel  gitStatusLabel;
    private JProgressBar contextBar;
    private JPanel phaseStripPanel;
    private String  workspaceProjectTypeLabel = "Unknown project";
    private String  lastAnnouncedTelemetryActivity = "";
    private Content tabContent;
    private boolean titleGenerated = false;
    private int currentContextUsagePercent = 0;

    // Auto-fix loop state — reset on each new user message
    private int     buildFixAttempts       = 0;
    private String  lastCompileFingerprint = "";
    private String  lastTestFingerprint    = "";
    // When true, a successful compile after a test-fix immediately re-runs the tests
    private boolean inTestFixLoop          = false;
    private String  testFixName            = null;

    // Chat display
    private JTextPane      chatPane;
    private StyledDocument chatDoc;

    // Input
    private JTextArea    promptArea;
    private JButton      sendBtn;
    private JButton      stopBtn;
    private JButton      clearContextBtn;
    private JButton      clearAttachmentsBtn;
    private JProgressBar spinner;
    private JPanel       attachmentsPanel;
    private JPanel       attachmentCardsPanel;
    private JLabel       attachmentHintLabel;

    private int estimatedInputTokens = 0;
    private int estimatedOutputTokens = 0;
    private static final int CONTEXT_BUDGET_TOKENS = 12_000;
    private static final int MAX_PROMPT_HISTORY = 10;

    private volatile boolean isGenerating = false;
    private volatile boolean stopRequested = false;
    private Thread currentChatThread;

    // Streaming state (all accessed on EDT only)
    private final Timer         blinkTimer;
    private final Timer         statusPulseTimer;
    private       boolean       streaming       = false;
    private       boolean       cursorOn        = false;
    private final StringBuilder assistantBuffer = new StringBuilder();

    // Conversation history
    private final List<ChatMessage> history = new ArrayList<>();
    private final List<String> promptHistory = new ArrayList<>();
    private int promptHistoryIndex = -1;
    private String promptHistoryDraft = "";

    // Track newly created files for Git
    private final List<String> newlyCreatedFiles = new ArrayList<>();

    // Attachments dropped by the user for the next prompt
    private final List<AttachmentData> pendingAttachments = new ArrayList<>();

    // Hybrid RAG: initialized lazily so it doesn't block the EDT constructor
    private volatile ContextCollector contextCollector;
    private volatile SkillMemory skillMemory;

    // Text styles
    private Style userRoleStyle;
    private Style userTextStyle;
    private Style assistantRoleStyle;
    private Style assistantTextStyle;
    private Style systemStyle;
    private Style cursorStyle;
    private String statusBaseActivity = "Ready";
    private int statusPulsePhase = 0;
    private final Map<String, JLabel> phaseChipLabels = new LinkedHashMap<>();

    public ChatPanel(@NotNull Project project) {
        this.project = project;
        project.putUserData(PANEL_KEY, this);
        blinkTimer = new Timer(500, e -> toggleBlink());
        blinkTimer.setRepeats(true);
        statusPulseTimer = new Timer(450, e -> toggleStatusPulse());
        statusPulseTimer.setRepeats(true);

        root = new JPanel(new BorderLayout());
        root.add(buildToolbar(),    BorderLayout.NORTH);
        root.add(buildChatArea(),   BorderLayout.CENTER);
        root.add(buildInputPanel(), BorderLayout.SOUTH);

        // Ctrl+Shift+N = New Chat from anywhere in the panel
        javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_N,
                java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        root.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, "newChat");
        root.getActionMap().put("newChat", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { clearConversation(); }
        });
    }

    public JTextPane getChatPane() { return chatPane; }
    public JTextArea getPromptArea() { return promptArea; }
    public JButton getSendBtn() { return sendBtn; }
    public JButton getStopBtn() { return stopBtn; }
    public JButton getClearContextBtn() { return clearContextBtn; }
    public JProgressBar getSpinner() { return spinner; }
    public Timer getBlinkTimer() { return blinkTimer; }
    public Timer getStatusPulseTimer() { return statusPulseTimer; }
    public JLabel getTitleLabel() { return titleLabel; }
    public JLabel getWorkspaceStatusLabel() { return workspaceStatusLabel; }
    public JLabel getActivityLabel() { return activityLabel; }
    public JLabel getFileHistoryLabel() { return fileHistoryLabel; }
    public JLabel getGitStatusLabel() { return gitStatusLabel; }
    public JProgressBar getContextBar() { return contextBar; }
    public boolean isTitleGenerated() { return titleGenerated; }
    public int getBuildFixAttempts() { return buildFixAttempts; }
    public List<ChatMessage> getHistory() { return history; }
    public List<String> getNewlyCreatedFiles() { return newlyCreatedFiles; }
    public Style getUserRoleStyle() { return userRoleStyle; }
    public Style getUserTextStyle() { return userTextStyle; }
    public Style getAssistantRoleStyle() { return assistantRoleStyle; }
    public Style getAssistantTextStyle() { return assistantTextStyle; }
    public Style getSystemStyle() { return systemStyle; }
    public Style getCursorStyle() { return cursorStyle; }

    // -------------------------------------------------------------------------
    // Toolbar — title on the left, gear on the right
    // -------------------------------------------------------------------------

    JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 8, 6, 8)
        ));
        bar.setOpaque(true);

        titleLabel = new JLabel("  New Chat");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));

        modeCombo = new JComboBox<>(new String[]{"PLANNING", "EDITING", "BYPASS"});
        modeCombo.setSelectedItem("PLANNING");
        modeCombo.addActionListener(e -> mode = (String) modeCombo.getSelectedItem());

        workspaceStatusLabel = new JLabel();
        workspaceStatusLabel.setFont(workspaceStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        workspaceStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        workspaceProjectTypeLabel = ChatPanelSupport.projectTypeLabel(project);
        refreshWorkspaceStatus();

        activityLabel = new JLabel();
        activityLabel.setFont(activityLabel.getFont().deriveFont(Font.BOLD, 12f));
        activityLabel.setOpaque(true);
        activityLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));

        fileHistoryLabel = new JLabel();
        fileHistoryLabel.setFont(fileHistoryLabel.getFont().deriveFont(Font.PLAIN, 11f));
        fileHistoryLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

        gitStatusLabel = new JLabel();
        gitStatusLabel.setFont(gitStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        gitStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

        contextBar = new JProgressBar(0, 100);
        contextBar.setStringPainted(true);
        contextBar.setPreferredSize(new Dimension(140, 16));
        contextBar.setMaximumSize(new Dimension(220, 18));
        refreshTelemetry("Ready", null);
        refreshVersionControlStatus();
        phaseStripPanel = buildPhaseStrip();

        clearContextBtn = new JButton("Clear Context");
        clearContextBtn.setToolTipText("Clear chat display and reset conversation context (Ctrl+Shift+N)");
        clearContextBtn.addActionListener(e -> clearConversation());

        JButton gearBtn = new JButton("Settings", AllIcons.General.Settings);
        gearBtn.setToolTipText("Open settings for local LLM URL, model, MCP, GitLab, and Jira");
        gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearBtn.setFocusable(false);
        gearBtn.setMargin(new Insets(3, 10, 3, 10));
        gearBtn.setHorizontalTextPosition(SwingConstants.RIGHT);
        gearBtn.setVerticalTextPosition(SwingConstants.CENTER);
        gearBtn.setIconTextGap(6);
        gearBtn.setPreferredSize(new Dimension(128, 28));
        gearBtn.addActionListener(e -> showSettingsDialog());

        JPanel headerRow = new JPanel(new BorderLayout(8, 0));
        headerRow.setOpaque(false);
        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerLeft.setOpaque(false);
        headerLeft.add(titleLabel);
        headerLeft.add(Box.createHorizontalStrut(8));
        headerLeft.add(new JLabel("Mode:"));
        headerLeft.add(modeCombo);
        headerLeft.add(clearContextBtn);
        headerRow.add(headerLeft, BorderLayout.WEST);

        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.setOpaque(false);
        JPanel statusLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusLeft.setOpaque(false);
        statusLeft.add(activityLabel);
        statusLeft.add(workspaceStatusLabel);
        statusLeft.add(contextBar);
        statusLeft.add(fileHistoryLabel);
        statusLeft.add(gitStatusLabel);
        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        statusRight.setOpaque(false);
        statusRight.add(gearBtn);
        statusRow.add(statusLeft, BorderLayout.WEST);
        statusRow.add(statusRight, BorderLayout.EAST);

        bar.add(headerRow);
        bar.add(Box.createVerticalStrut(4));
        bar.add(statusRow);
        if (phaseStripPanel != null) {
            phaseStripPanel.setVisible(false);
        }

        return bar;
    }

    private JPanel buildPhaseStrip() {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        for (String phase : List.of("Ready", "Planning", "Thinking", "Working", "Debugging", "Testing", "Reviewing", "Running", "Interrupted")) {
            JLabel chip = new JLabel(phase);
            chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 11f));
            chip.setOpaque(true);
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)
            ));
            chip.setForeground(UIManager.getColor("Label.disabledForeground"));
            phaseChipLabels.put(phase, chip);
            strip.add(chip);
        }
        updatePhaseStrip("Ready");
        return strip;
    }

    // -------------------------------------------------------------------------
    // Settings dialog
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        Window parent = SwingUtilities.getWindowAncestor(root);
        JDialog dialog = new JDialog(parent, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        JScrollPane scrollPane = new JScrollPane(buildSettingsForm(dialog));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        dialog.setContentPane(scrollPane);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(720, 640));
        dialog.setLocationRelativeTo(root);
        dialog.setResizable(true);
        dialog.setVisible(true);
    }

    private JPanel buildSettingsForm(JDialog dialog) {
        PluginSettings s = PluginSettings.getInstance();

        JTextField        endpointField = new JTextField(s.getEndpoint(), 28);
        JComboBox<String> modelCombo    = new JComboBox<>();
        JLabel            statusLabel   = new JLabel(" ");
        JButton           refreshBtn    = new JButton("Refresh Models");
        JButton           saveBtn       = new JButton("Save");
        JCheckBox         contextCheck  = new JCheckBox("Include full file contents in context", s.isIncludeFullContext());
        JTextField        gitlabCliPathField = new JTextField(s.getGitlabCliPath(), 28);
        JTextField        gitlabProjectField = new JTextField(s.getGitlabProject(), 28);
        JTextField        gitlabApiUrlField  = new JTextField(s.getGitlabApiUrl(), 28);
        JPasswordField    gitlabTokenField   = new JPasswordField(s.getGitlabToken(), 28);
        JTextField        jiraBaseUrlField   = new JTextField(s.getJiraBaseUrl(), 28);
        JTextField        jiraEmailField     = new JTextField(s.getJiraEmail(), 28);
        JPasswordField    jiraTokenField     = new JPasswordField(s.getJiraToken(), 28);
        JTextField        mcpServerUrlField  = new JTextField(s.getMcpServerUrl(), 28);
        JTextField        mcpProtocolField   = new JTextField(s.getMcpProtocolVersion(), 28);
        JLabel            integrationStatusLabel = new JLabel(" ");
        JButton           testGitLabBtn     = new JButton("Test GitLab");
        JButton           testJiraBtn        = new JButton("Test Jira");
        JButton           testMcpBtn         = new JButton("Test MCP");
        JButton           testDockerBtn      = new JButton("Test Docker");
        JButton           testHelmBtn        = new JButton("Test Helm");
        JButton           manageSkillsBtn   = new JButton("Manage Skills");

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        integrationStatusLabel.setFont(integrationStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        if (s.getModel() != null && !s.getModel().isBlank()) {
            modelCombo.addItem(s.getModel());
            modelCombo.setSelectedItem(s.getModel());
        }

        refreshBtn.addActionListener(e -> {
            String ep = endpointField.getText().trim();
            refreshBtn.setEnabled(false);
            statusLabel.setText("Fetching models…");
            daemon(() -> {
                try {
                    List<String> models = new LocalLLMClient(ep).fetchModels();
                    SwingUtilities.invokeLater(() -> {
                        modelCombo.removeAllItems();
                        models.forEach(modelCombo::addItem);
                        if (s.getModel() != null && models.contains(s.getModel())) {
                            modelCombo.setSelectedItem(s.getModel());
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

        testGitLabBtn.addActionListener(e -> runIntegrationTest(
                testGitLabBtn,
                integrationStatusLabel,
                "GitLab",
                () -> IntegrationAccessUtil.testGitLab(snapshotSettings(
                        endpointField.getText(),
                        modelCombo,
                        contextCheck,
                        gitlabCliPathField,
                        gitlabProjectField,
                        gitlabApiUrlField,
                        gitlabTokenField,
                        jiraBaseUrlField,
                        jiraEmailField,
                        jiraTokenField,
                        mcpServerUrlField,
                        mcpProtocolField
                ))));

        testJiraBtn.addActionListener(e -> runIntegrationTest(
                testJiraBtn,
                integrationStatusLabel,
                "Jira",
                () -> IntegrationAccessUtil.testJira(snapshotSettings(
                        endpointField.getText(),
                        modelCombo,
                        contextCheck,
                        gitlabCliPathField,
                        gitlabProjectField,
                        gitlabApiUrlField,
                        gitlabTokenField,
                        jiraBaseUrlField,
                        jiraEmailField,
                        jiraTokenField,
                        mcpServerUrlField,
                        mcpProtocolField
                ))));

        testMcpBtn.addActionListener(e -> runIntegrationTest(
                testMcpBtn,
                integrationStatusLabel,
                "MCP",
                () -> IntegrationAccessUtil.testMcp(snapshotSettings(
                        endpointField.getText(),
                        modelCombo,
                        contextCheck,
                        gitlabCliPathField,
                        gitlabProjectField,
                        gitlabApiUrlField,
                        gitlabTokenField,
                        jiraBaseUrlField,
                        jiraEmailField,
                        jiraTokenField,
                        mcpServerUrlField,
                        mcpProtocolField
                ))));

        testDockerBtn.addActionListener(e -> runIntegrationTest(
                testDockerBtn,
                integrationStatusLabel,
                "Docker",
                IntegrationAccessUtil::testDocker));

        testHelmBtn.addActionListener(e -> runIntegrationTest(
                testHelmBtn,
                integrationStatusLabel,
                "Helm",
                IntegrationAccessUtil::testHelm));

        manageSkillsBtn.addActionListener(e -> {
            Window window = SwingUtilities.getWindowAncestor(dialog);
            SkillManagerDialog.show(window, getSkillMemory());
        });

        saveBtn.addActionListener(e -> {
            s.setEndpoint(endpointField.getText().trim());
            s.setIncludeFullContext(contextCheck.isSelected());
            Object sel = modelCombo.getSelectedItem();
            if (sel != null && !sel.toString().isBlank()) s.setModel(sel.toString());
            s.setGitlabCliPath(gitlabCliPathField.getText().trim());
            s.setGitlabProject(gitlabProjectField.getText().trim());
            s.setGitlabApiUrl(gitlabApiUrlField.getText().trim());
            s.setGitlabToken(new String(gitlabTokenField.getPassword()).trim());
            s.setJiraBaseUrl(jiraBaseUrlField.getText().trim());
            s.setJiraEmail(jiraEmailField.getText().trim());
            s.setJiraToken(new String(jiraTokenField.getPassword()).trim());
            s.setMcpServerUrl(mcpServerUrlField.getText().trim());
            s.setMcpProtocolVersion(mcpProtocolField.getText().trim());
            dialog.dispose();
        });

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
        fc.gridy = 3; form.add(contextCheck, fc);
        addSectionHeader(form, "Integrations", lc, fc, 4);
        addFormRow(form, "GitLab CLI:", gitlabCliPathField, lc, fc, 5);
        addFormRow(form, "GitLab project:", gitlabProjectField, lc, fc, 6);
        addFormRow(form, "GitLab API URL:", gitlabApiUrlField, lc, fc, 7);
        addFormRow(form, "GitLab token:", gitlabTokenField, lc, fc, 8);
        fc.gridy = 9; form.add(testGitLabBtn, fc);
        addFormRow(form, "Jira base URL:", jiraBaseUrlField, lc, fc, 10);
        addFormRow(form, "Jira email:", jiraEmailField, lc, fc, 11);
        addFormRow(form, "Jira token:", jiraTokenField, lc, fc, 12);
        fc.gridy = 13; form.add(testJiraBtn, fc);
        addFormRow(form, "MCP server URL:", mcpServerUrlField, lc, fc, 14);
        addFormRow(form, "MCP protocol:", mcpProtocolField, lc, fc, 15);
        fc.gridy = 16;
        JPanel mcpButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        mcpButtonRow.setOpaque(false);
        mcpButtonRow.add(testMcpBtn);
        mcpButtonRow.add(testDockerBtn);
        mcpButtonRow.add(testHelmBtn);
        mcpButtonRow.add(manageSkillsBtn);
        form.add(mcpButtonRow, fc);
        fc.gridy = 17; form.add(saveBtn,     fc);
        fc.gridy = 18; form.add(statusLabel, fc);
        fc.gridy = 19; form.add(integrationStatusLabel, fc);

        return form;
    }

    private static void addFormRow(JPanel p, String label, JComponent field,
                                   GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridx = 0; lc.gridy = row; p.add(new JLabel(label), lc);
        fc.gridx = 1; fc.gridy = row; p.add(field, fc);
    }

    private static void addSectionHeader(JPanel p, String text, GridBagConstraints lc, GridBagConstraints fc, int row) {
        JLabel header = new JLabel(text);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        lc.gridx = 0;
        lc.gridy = row;
        lc.gridwidth = GridBagConstraints.REMAINDER;
        lc.insets = new Insets(12, 0, 2, 0);
        p.add(header, lc);
        lc.gridwidth = 1;
        lc.insets = new Insets(5, 0, 5, 10);
        fc.gridy = row;
    }

    private static PluginSettings snapshotSettings(String endpoint,
                                                   JComboBox<String> modelCombo,
                                                   JCheckBox contextCheck,
                                                   JTextField gitlabCliPathField,
                                                   JTextField gitlabProjectField,
                                                   JTextField gitlabApiUrlField,
                                                   JPasswordField gitlabTokenField,
                                                   JTextField jiraBaseUrlField,
                                                   JTextField jiraEmailField,
                                                   JPasswordField jiraTokenField,
                                                   JTextField mcpServerUrlField,
                                                   JTextField mcpProtocolField) {
        PluginSettings snapshot = new PluginSettings();
        snapshot.setEndpoint(endpoint == null ? "" : endpoint.trim());
        Object model = modelCombo.getSelectedItem();
        if (model != null) {
            snapshot.setModel(model.toString().trim());
        }
        snapshot.setIncludeFullContext(contextCheck.isSelected());
        snapshot.setGitlabCliPath(gitlabCliPathField.getText().trim());
        snapshot.setGitlabProject(gitlabProjectField.getText().trim());
        snapshot.setGitlabApiUrl(gitlabApiUrlField.getText().trim());
        snapshot.setGitlabToken(new String(gitlabTokenField.getPassword()).trim());
        snapshot.setJiraBaseUrl(jiraBaseUrlField.getText().trim());
        snapshot.setJiraEmail(jiraEmailField.getText().trim());
        snapshot.setJiraToken(new String(jiraTokenField.getPassword()).trim());
        snapshot.setMcpServerUrl(mcpServerUrlField.getText().trim());
        snapshot.setMcpProtocolVersion(mcpProtocolField.getText().trim());
        return snapshot;
    }

    private static void runIntegrationTest(JButton button,
                                           JLabel statusLabel,
                                           String integrationName,
                                           java.util.function.Supplier<IntegrationAccessUtil.IntegrationTestResult> task) {
        button.setEnabled(false);
        statusLabel.setText(integrationName + ": testing…");
        daemon(() -> {
            IntegrationAccessUtil.IntegrationTestResult result;
            try {
                result = task.get();
            } catch (Exception ex) {
                result = new IntegrationAccessUtil.IntegrationTestResult(false, ex.getMessage());
            }
            final IntegrationAccessUtil.IntegrationTestResult finalResult = result;
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                statusLabel.setText(IntegrationAccessUtil.summarizeResult(integrationName, finalResult));
                statusLabel.setToolTipText(statusLabel.getText());
            });
        });
    }

    // -------------------------------------------------------------------------
    // Chat area — styled JTextPane, always-on scroll bar
    // -------------------------------------------------------------------------

    JScrollPane buildChatArea() {
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setMargin(new Insets(10, 12, 10, 12));
        chatPane.setBackground(UIManager.getColor("Editor.background"));
        chatDoc  = chatPane.getStyledDocument();
        initStyles();

        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void initStyles() {
        Style base = StyleContext.getDefaultStyleContext()
                                 .getStyle(StyleContext.DEFAULT_STYLE);

        userRoleStyle = chatPane.addStyle("userRole", base);
        StyleConstants.setForeground(userRoleStyle, new Color(0x4EC9B0));
        StyleConstants.setBold(userRoleStyle, true);
        StyleConstants.setFontSize(userRoleStyle, 16);

        userTextStyle = chatPane.addStyle("userText", base);
        StyleConstants.setForeground(userTextStyle, new Color(0xD4D4D4));
        StyleConstants.setFontFamily(userTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(userTextStyle, 18);

        assistantRoleStyle = chatPane.addStyle("assistantRole", base);
        StyleConstants.setForeground(assistantRoleStyle, new Color(0x569CD6));
        StyleConstants.setBold(assistantRoleStyle, true);
        StyleConstants.setFontSize(assistantRoleStyle, 16);

        assistantTextStyle = chatPane.addStyle("assistantText", base);
        StyleConstants.setForeground(assistantTextStyle, new Color(0xE8E8E8));
        StyleConstants.setFontFamily(assistantTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(assistantTextStyle, 18);

        systemStyle = chatPane.addStyle("system", base);
        StyleConstants.setForeground(systemStyle, new Color(0xCE9178));
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontSize(systemStyle, 16);

        cursorStyle = chatPane.addStyle("cursor", base);
        StyleConstants.setForeground(cursorStyle, new Color(0x569CD6));
        StyleConstants.setBold(cursorStyle, true);
    }

    // -------------------------------------------------------------------------
    // Input panel — 4-row textarea, Send bottom-right, Clear bottom-left
    // -------------------------------------------------------------------------

    JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(8, 12, 12, 12)
        ));

        attachmentsPanel = new JPanel(new BorderLayout(0, 8));
        attachmentsPanel.setOpaque(false);
        Color attachmentBorderColor = UIManager.getColor("Separator.foreground");
        if (attachmentBorderColor == null) attachmentBorderColor = Color.GRAY;
        attachmentsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(attachmentBorderColor),
                BorderFactory.createEmptyBorder(5, 6, 5, 6)
        ));
        JPanel attachmentHeader = new JPanel(new BorderLayout(8, 0));
        attachmentHeader.setOpaque(false);
        JLabel attachmentTitle = new JLabel("Attachments");
        attachmentTitle.setFont(attachmentTitle.getFont().deriveFont(Font.BOLD, 12f));
        attachmentHintLabel = new JLabel();
        attachmentHintLabel.setFont(attachmentHintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        attachmentHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        if (attachmentHintLabel.getForeground() == null) {
            attachmentHintLabel.setForeground(Color.GRAY);
        }
        JPanel attachmentTitlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        attachmentTitlePanel.setOpaque(false);
        attachmentTitlePanel.add(attachmentTitle);
        attachmentTitlePanel.add(Box.createHorizontalStrut(8));
        attachmentTitlePanel.add(attachmentHintLabel);

        clearAttachmentsBtn = new JButton("Clear Attachments");
        clearAttachmentsBtn.setToolTipText("Remove all dropped attachments before sending");
        clearAttachmentsBtn.addActionListener(e -> clearAttachments());
        clearAttachmentsBtn.setVisible(false);
        clearAttachmentsBtn.setFocusable(false);
        attachmentHeader.add(attachmentTitlePanel, BorderLayout.WEST);
        attachmentHeader.add(clearAttachmentsBtn, BorderLayout.EAST);

        attachmentCardsPanel = new JPanel();
        attachmentCardsPanel.setOpaque(false);
        attachmentCardsPanel.setLayout(new BoxLayout(attachmentCardsPanel, BoxLayout.Y_AXIS));

        JScrollPane attachmentScroll = new JScrollPane(attachmentCardsPanel);
        attachmentScroll.setBorder(BorderFactory.createEmptyBorder());
        attachmentScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        attachmentScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        attachmentScroll.getViewport().setOpaque(false);
        attachmentScroll.setOpaque(false);
        attachmentScroll.setPreferredSize(new Dimension(0, 44));
        attachmentScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        attachmentsPanel.add(attachmentHeader, BorderLayout.NORTH);
        attachmentsPanel.add(attachmentScroll, BorderLayout.CENTER);
        attachmentsPanel.setTransferHandler(buildAttachmentTransferHandler());
        refreshAttachmentStrip();

        promptArea = new JTextArea(4, 0);
        promptArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setMargin(new Insets(6, 8, 6, 8));
        promptArea.setToolTipText(null);
        // Enter = send   |   Shift+Enter = newline
        promptArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        promptArea.insert("\n", promptArea.getCaretPosition());
                        e.consume();
                    } else {
                        e.consume();
                        sendMessage();
                    }
                    return;
                }

                if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (shouldUsePromptHistoryNavigation(e.getKeyCode())) {
                        e.consume();
                        boolean up = e.getKeyCode() == KeyEvent.VK_UP;
                        ChatPanelSupport.PromptHistoryState state = ChatPanelSupport.navigatePromptHistory(
                                promptHistory,
                                promptArea.getText(),
                                promptHistoryIndex,
                                promptHistoryDraft,
                                up
                        );
                        promptHistoryIndex = state.index();
                        promptHistoryDraft = state.draft();
                        promptArea.setText(state.displayedText());
                        promptArea.setCaretPosition(promptArea.getText().length());
                        return;
                    }
                }

                if (shouldResetPromptHistoryNavigation(e)) {
                    resetPromptHistoryNavigation();
                }
            }
        });

        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        promptScroll.setTransferHandler(buildAttachmentTransferHandler());
        promptArea.setTransferHandler(buildAttachmentTransferHandler());
        root.setTransferHandler(buildAttachmentTransferHandler());

        sendBtn = new JButton("Send");
        sendBtn.setPreferredSize(new Dimension(80, 28));
        sendBtn.addActionListener(e -> sendMessage());

        stopBtn = new JButton("Stop", AllIcons.Actions.Suspend);
        stopBtn.setPreferredSize(new Dimension(80, 28));
        stopBtn.setVisible(false);
        stopBtn.addActionListener(e -> {
            stopRequested = true;
            if (currentChatThread != null) {
                currentChatThread.interrupt();
            }
            appendSystemMessage("Interrupted by user.");
            setLoading(false);
        });

        spinner = new JProgressBar();
        spinner.setIndeterminate(false);
        spinner.setPreferredSize(new Dimension(80, 14));
        spinner.setVisible(false);

        JPanel ctrlRow  = new JPanel(new BorderLayout(4, 0));
        JPanel leftCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftCtrl.add(spinner);
        ctrlRow.add(leftCtrl, BorderLayout.WEST);
        JPanel rightCtrl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightCtrl.add(stopBtn);
        rightCtrl.add(sendBtn);
        ctrlRow.add(rightCtrl, BorderLayout.EAST);

        panel.add(attachmentsPanel, BorderLayout.NORTH);
        panel.add(promptScroll, BorderLayout.CENTER);
        panel.add(ctrlRow,      BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Send — streams response token by token
    // -------------------------------------------------------------------------

    private void sendMessage() {
        String text = promptArea.getText().trim();
        List<AttachmentData> attachments = pendingAttachments.isEmpty()
                ? List.of()
                : new ArrayList<>(pendingAttachments);
        if (text.isEmpty() && attachments.isEmpty()) return;
        if (text.isEmpty()) {
            text = AttachmentUtil.suggestedUserPrompt(attachments);
        }

        if (ChatPanelSupport.isRunTestsIntent(text)) {
            recordPromptHistory(text);
            newlyCreatedFiles.clear();
            promptArea.setText("");
            pendingAttachments.clear();
            refreshAttachmentStrip();
            resetPromptHistoryNavigation();
            appendSystemMessage("Running tests now…");
            refreshTelemetry("Testing", null);
            scheduleTestRun(null, null, null, AgentTask.TaskType.GENERAL);
            return;
        }

        if (ChatPanelSupport.isStructureOnlyResponse(text)) {
            String structure = ChatPanelSupport.stripProjectStructureWrappers(
                    ChatPanelSupport.formatProjectStructureResponse(project));
            if (structure.isBlank()) {
                appendSystemMessage("Project structure is unavailable for this workspace.");
                return;
            }
            estimatedInputTokens += ChatPanelSupport.estimateTokens(text);
            estimatedOutputTokens += ChatPanelSupport.estimateTokens(structure);
            refreshWorkspaceStatus();
            appendUserMessage(text);
            history.add(new ChatMessage("user", text));
            appendAssistantMessage(structure);
            history.add(new ChatMessage("assistant", structure));
            promptArea.setText("");
            pendingAttachments.clear();
            refreshAttachmentStrip();
            resetPromptHistoryNavigation();
            if (!titleGenerated && text != null) {
                titleGenerated = true;
                generateTitle(text);
            }
            refreshTelemetry("Idle", null);
            return;
        }

        recordPromptHistory(text);
        newlyCreatedFiles.clear();
        buildFixAttempts       = 0;
        lastCompileFingerprint = "";
        lastTestFingerprint    = "";
        inTestFixLoop          = false;
        testFixName            = null;

        PluginSettings s  = PluginSettings.getInstance();
        String model      = s.getModel();
        String endpoint   = s.getEndpoint();

        if (model == null || model.isBlank()) {
            appendSystemMessage("No model configured — click ⚙ to open Settings.");
            return;
        }

        if (ChatPanelSupport.isGitAddCreatedFilesIntent(text)) {
            if (newlyCreatedFiles.isEmpty()) {
                appendSystemMessage("No tracked created files are available to add to Git.");
                return;
            }
            appendSystemMessage("Created files:\n- " + String.join("\n- ", newlyCreatedFiles));
            scheduleGitAdd();
            promptArea.setText("");
            return;
        }

        // Auto-switch to EDITING for tasks that require file changes
        AgentTask.TaskType detectedType = new PlannerAgent().detectTaskType(text);
        boolean patchAttachment = AttachmentUtil.containsPatchAttachment(attachments);
        boolean jiraAttachment = AttachmentUtil.containsJiraTicketAttachment(attachments);
        boolean angularIntent = AttachmentUtil.containsAngularBuildIntent(text);
        boolean readmeIntent = ChatPanelSupport.isReadmeIntent(text);
        boolean containerIntent = ChatPanelSupport.isDockerOrHelmIntent(text);
        if ("PLANNING".equals(mode) && modeCombo != null
                && (detectedType == AgentTask.TaskType.GENERATE_TESTS
                    || detectedType == AgentTask.TaskType.FIX_BUG
                    || detectedType == AgentTask.TaskType.ADD_FEATURE
                    || detectedType == AgentTask.TaskType.REVIEW_COMMIT
                    || detectedType == AgentTask.TaskType.REFACTOR
                    || detectedType == AgentTask.TaskType.DOCUMENT
                    || patchAttachment
                    || jiraAttachment
                    || angularIntent
                    || containerIntent
                    || readmeIntent)) {
            modeCombo.setSelectedItem("EDITING");
            appendSystemMessage("Auto-switched to EDITING mode.");
        }
        refreshTelemetry("Thinking", null);

        // Add system message with context if history is empty or it's a new conversation
        if (history.isEmpty()) {
            // Hybrid RAG: retrieve only the most relevant files for this query
            String context = buildRagContext(text);
            String corrections = plugin.util.LLMCorrectionsUtil.loadCorrectionsForPrompt(project.getBasePath());
            // PromptBuilder: compact system prompt tuned for the configured model
            String envInfo = plugin.util.EnvironmentInfoCollector.collectForPrompt(project);
            String memoryInfo = buildMemoryContext(text, attachments);
            if (readmeIntent) {
                String readmeContext = ChatPanelSupport.buildReadmeContext(project);
                if (!readmeContext.isBlank()) {
                    if (!memoryInfo.isBlank()) {
                        memoryInfo += "\n\n";
                    }
                    memoryInfo += readmeContext;
                }
            }
            String containerInfo = ChatPanelSupport.buildContainerContext(project);
            if (!containerInfo.isBlank()) {
                if (!memoryInfo.isBlank()) {
                    memoryInfo += "\n\n";
                }
                memoryInfo += containerInfo;
            }
            if (ChatPanelSupport.isAnalysisIntent(text)) {
                String analysisContext = ChatPanelSupport.buildProjectAnalysisContext(project);
                if (!analysisContext.isBlank()) {
                    if (!memoryInfo.isBlank()) {
                        memoryInfo += "\n\n";
                    }
                    memoryInfo += analysisContext;
                }
            }
            String reviewContext = buildReviewContext(text, attachments);
            if (!reviewContext.isBlank()) {
                if (!memoryInfo.isBlank()) {
                    memoryInfo += "\n\n";
                }
                memoryInfo += "# Commit Review\n" + reviewContext;
            }
            String integrationInfo = IntegrationAccessUtil.buildPromptSummary(s);
            if (integrationInfo != null && !integrationInfo.isBlank()) {
                if (!memoryInfo.isBlank()) {
                    memoryInfo += "\n\n";
                }
                memoryInfo += "# Integrations\n" + integrationInfo;
            }
            String systemInstructions = PromptBuilder.buildSystemPrompt(mode, corrections, envInfo, memoryInfo);

            history.add(new ChatMessage("system", systemInstructions));

            // Inject RAG context (already focused — usually fits in one block)
            if (!context.isBlank()) {
                if (context.length() > 6000) {
                    List<String> chunks = ChatPanelSupport.splitIntoChunks(context, 6000);
                    for (int i = 0; i < chunks.size(); i++) {
                        history.add(new ChatMessage("user", "Retrieved Context (Part " + (i + 1) + "/" + chunks.size() + "):\n" + chunks.get(i)));
                        history.add(new ChatMessage("assistant", "Received context part " + (i + 1) + ". Please continue."));
                    }
                } else {
                    history.add(new ChatMessage("user", "Retrieved Context (RAG — most relevant files for this query):\n" + context));
                    history.add(new ChatMessage("assistant", "Retrieved context loaded. How can I help you?"));
                }
            }
        } else {
            // Update mode in system message if it already exists
            ChatMessage first = history.get(0);
            if ("system".equals(first.role())) {
                String updatedSystemPrompt = first.content().replaceFirst("Mode: (PLANNING|EDITING|BYPASS)", "Mode: " + mode);
                history.set(0, new ChatMessage("system", updatedSystemPrompt));
            }
        }

        appendUserMessage(text);
        if (!attachments.isEmpty()) {
            String attachmentBlock = AttachmentUtil.buildPromptBlock(attachments);
            if (!attachmentBlock.isBlank()) {
                history.add(new ChatMessage("user", attachmentBlock));
            }
        }
        
        // Split large user message into chunks if necessary (max 6000 chars per part)
        if (text.length() > 6000) {
            List<String> chunks = ChatPanelSupport.splitIntoChunks(text, 6000);
            for (int i = 0; i < chunks.size() - 1; i++) {
                history.add(new ChatMessage("user", "Message Part " + (i + 1) + "/" + chunks.size() + ":\n" + chunks.get(i)));
                history.add(new ChatMessage("assistant", "Part " + (i + 1) + " received. Please send the next part."));
            }
            history.add(new ChatMessage("user", "Final Part " + chunks.size() + "/" + chunks.size() + ":\n" + chunks.get(chunks.size() - 1)));
        } else {
            history.add(new ChatMessage("user", text));
        }

        promptArea.setText("");
        pendingAttachments.clear();
        refreshAttachmentStrip();
        resetPromptHistoryNavigation();

        if (shouldRememberSkill(text, attachments)) {
            rememberSkillFromPrompt(text, attachments);
        }

        beginAssistantMessage();
        blinkTimer.start();
        setLoading(true);

        streamAndHandle(model, endpoint, text, true, detectedType, attachments);
    }

    private void streamAndHandle(String model, String endpoint, String userText, boolean canRetry, AgentTask.TaskType taskType) {
        streamAndHandle(model, endpoint, userText, canRetry, taskType, List.of());
    }

    private void streamAndHandle(String model, String endpoint, String userText, boolean canRetry, AgentTask.TaskType taskType,
                                 List<AttachmentData> attachments) {
        workspaceProjectTypeLabel = ChatPanelSupport.projectTypeLabel(project);
        refreshTelemetry("Thinking", null);
        List<ChatMessage> snapshot = new ArrayList<>(history);
        // History management: preserve system prompt, initial project context, and recent conversation
        snapshot = ChatPanelSupport.trimConversationHistory(snapshot, CONTEXT_BUDGET_TOKENS);

        List<ChatMessage> finalSnapshot = snapshot;
        estimatedInputTokens += ChatPanelSupport.estimateTokens(finalSnapshot);
        refreshWorkspaceStatus();
        refreshTelemetry("Thinking", finalSnapshot);
        currentChatThread = new Thread(() -> {
            try {
                stopRequested = false;
                new LocalLLMClient(endpoint).streamChat(model, finalSnapshot, attachments,
                        token -> {
                            if (stopRequested) throw new RuntimeException("STREAM_INTERRUPTED");
                            SwingUtilities.invokeLater(() -> appendToken(token));
                        });
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    String fullResponse = assistantBuffer.toString();
                    history.add(new ChatMessage("assistant", fullResponse));

                    if (!titleGenerated && userText != null) {
                        titleGenerated = true;
                        generateTitle(userText);
                    }

                    maybeUpdateSkillFromSession(userText, fullResponse, attachments);

                    if (taskType == AgentTask.TaskType.REVIEW_COMMIT) {
                        refreshTelemetry("Reviewing", finalSnapshot);
                        maybePublishGitLabReview(userText, fullResponse);
                        if ("PLANNING".equals(mode)) {
                            appendSystemMessage("Commit review completed in planning mode. No files were changed.");
                        }
                    } else if ("EDITING".equals(mode)) {
                        refreshTelemetry("Working", finalSnapshot);
                        String responseLower = fullResponse.toLowerCase();
                        boolean hasFileOps = fullResponse.contains("<CREATE_FILE") ||
                                             fullResponse.contains("<MODIFY_FILE") ||
                                             fullResponse.contains("<CREATE_FOLDER") ||
                                             fullResponse.contains("<DELETE_FILE") ||
                                             fullResponse.contains("<DELETE_FOLDER");
                        // Detect wrong-case tags (e.g. <modify_file> instead of <MODIFY_FILE>)
                        boolean hasMalformedTags = !hasFileOps && (
                                responseLower.contains("<create_file") || responseLower.contains("<modify_file") ||
                                responseLower.contains("<create_folder") || responseLower.contains("<delete_file") ||
                                responseLower.contains("<delete_folder"));
                        boolean hasTests = fullResponse.contains("<RUN_TESTS") || fullResponse.contains("<CHECK_COMPILATION");
                        boolean hasCustomCommand = fullResponse.contains("<EXECUTE_COMMAND");

                        if (ChatPanelSupport.isProjectStructureIntent(userText)
                                && (ChatPanelSupport.isNonActionableModelResponse(fullResponse)
                                    || (hasCustomCommand && ChatPanelSupport.isStructureListingCommand(fullResponse)))) {
                            appendSystemMessage("Project structure requests are rendered directly. Ignoring shell listing output.");
                            String structure = ChatPanelSupport.stripProjectStructureWrappers(
                                    ChatPanelSupport.formatProjectStructureResponse(project));
                            if (!structure.isBlank()) {
                                history.add(new ChatMessage("assistant", structure));
                            }
                            refreshTelemetry("Idle", finalSnapshot);
                            setLoading(false);
                            return;
                        }

                        if (canRetry && ChatPanelSupport.isNonActionableModelResponse(fullResponse)
                                && (ChatPanelSupport.isReadmeIntent(userText)
                                    || ChatPanelSupport.isCommitReviewIntent(userText)
                                    || ChatPanelSupport.isSkillUpdateIntent(userText)
                                    || ChatPanelSupport.isDockerOrHelmIntent(userText)
                                    || ChatPanelSupport.isAnalysisIntent(userText)
                                    || taskType == AgentTask.TaskType.FIX_BUG)) {
                            recordMistakes(java.util.List.of("non-actionable-response"));
                            appendSystemMessage("⚠ Model returned a clarification or shell command instead of task output — auto-correcting…");
                            history.add(new ChatMessage("user", buildTaskRetryCorrection(userText, taskType, attachments)));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false, taskType, attachments);
                            return;
                        }
                        
                        if (canRetry && hasMalformedTags) {
                            recordMistakes(java.util.List.of("uppercase-xml-tags"));
                            appendSystemMessage("⚠ XML tags detected with wrong case — tags must be UPPERCASE (e.g. <MODIFY_FILE>, not <modify_file>). Auto-correcting…");
                            history.add(new ChatMessage("user",
                                    "CORRECTION REQUIRED: You used lowercase XML tags. All file operation tags must be UPPERCASE:\n" +
                                    "<MODIFY_FILE path=\"<detected-path>\">complete content</MODIFY_FILE>\n" +
                                    "<CREATE_FILE path=\"<detected-path>\">complete content</CREATE_FILE>\n" +
                                    "Re-send your response using UPPERCASE tags with the complete file content inside."));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false, taskType, attachments);
                        } else if (hasFileOps || hasTests || hasCustomCommand) {
                            FileOperationUtil.FileOpResult opResult = FileOperationUtil.processFileOperations(project, fullResponse);
                            if (opResult.createdFiles != null) {
                                newlyCreatedFiles.addAll(opResult.createdFiles);
                            }
                            if (opResult.warnings != null && !opResult.warnings.isEmpty()) {
                                opResult.warnings.forEach(this::appendSystemMessage);
                                injectBlockedWriteFeedback(opResult.warnings);
                            }
                            recordMistakes(opResult.mistakeKeys);
                            refreshVersionControlStatus();
                            if (opResult.runTests) {
                                appendSystemMessage("File operations applied. Running build check before tests…");
                                refreshTelemetry("Debugging", finalSnapshot);
                                scheduleBuildCheck(model, endpoint, taskType, true, opResult.testName);
                            } else if (opResult.checkCompilation) {
                                appendSystemMessage("Compilation check requested. Running build…");
                                refreshTelemetry("Debugging", finalSnapshot);
                                scheduleBuildCheck(model, endpoint, taskType, false, null);
                            } else if (opResult.customCommand != null) {
                                if (taskType == AgentTask.TaskType.GENERATE_TESTS
                                        && plugin.util.CommandIntentUtil.isStructureInspectionCommand(opResult.customCommand)) {
                                    recordMistakes(java.util.List.of("no-tree-for-tests"));
                                    appendSystemMessage("⚠ Structure-inspection command detected during a test-writing task — auto-correcting…");
                                    history.add(new ChatMessage("user",
                                            "CORRECTION REQUIRED: You used a directory listing command instead of writing tests. " +
                                            "Do NOT inspect the tree with EXECUTE_COMMAND. " +
                                            "Use <CREATE_FILE> or <MODIFY_FILE> to write a test directly in the project's detected language and test framework. " +
                                            "If no symbol is named, choose the most relevant source file from the retrieved context and write its test file now."));
                                    beginAssistantMessage();
                                    blinkTimer.start();
                                    streamAndHandle(model, endpoint, null, false, taskType, attachments);
                                    return;
                                }
                                if (!plugin.util.CommandIntentUtil.isCommandCompatibleWithCurrentPlatform(opResult.customCommand)) {
                                    recordMistakes(java.util.List.of("shell-incompatibility"));
                                    appendSystemMessage("⚠ Shell-incompatible command detected for this OS — auto-correcting…");
                                    history.add(new ChatMessage("user",
                                            "CORRECTION REQUIRED: The previous command is not compatible with the current operating system. " +
                                            "Rewrite it using commands that work on this platform. " +
                                            "If this is Windows, use PowerShell equivalents. If this is Linux or macOS, use Unix shell commands."));
                                    beginAssistantMessage();
                                    blinkTimer.start();
                                    streamAndHandle(model, endpoint, null, false, taskType, attachments);
                                    return;
                                }
                                appendSystemMessage("Custom command execution requested: " + opResult.customCommand + ". Running…");
                                refreshTelemetry("Running command", finalSnapshot);
                                scheduleCustomCommand(opResult.customCommand);
                            } else if (hasFileOps) {
                                appendSystemMessage("File operations applied. Running build check…");
                                refreshTelemetry("Debugging", finalSnapshot);
                                scheduleBuildCheck(model, endpoint, taskType, taskType == AgentTask.TaskType.GENERATE_TESTS, null);
                            }

                            if (fullResponse.contains("<GIT_ADD_NEW")) {
                                scheduleGitAdd();
                            }
                            return;
                        } else if (canRetry && (ChatPanelSupport.isFileOpIntent(userText) || fullResponse.contains("```"))) {
                            // Model either used a code block or gave plain text — auto-correct once.
                            // Trigger also when LLM responded with code blocks regardless of user phrasing
                            // (e.g. user said "yes" or "add test case" and LLM replied with markdown).
                            boolean usedCodeBlock = fullResponse.contains("```");
                            String correction = usedCodeBlock
                                ? "CORRECTION REQUIRED: You responded with file content inside a ``` code block. " +
                                  "A code block is display-only — it does NOT write to disk. "
                                : "CORRECTION REQUIRED: You described what to do instead of actually doing it. " +
                                  "A text description does NOT write to disk. ";
                            appendSystemMessage("⚠ Model did not use XML tags — auto-correcting…");
                            history.add(new ChatMessage("user",
                                    correction +
                                    "You MUST re-send your response as a raw XML tag with real code inside it. " +
                                    "Use the project's detected language and test framework. " +
                                    "Do not use markdown fences or plain text. Output ONLY the XML tag."));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false, taskType, attachments);
                            return;
                        } else {
                            recordMistakes(java.util.List.of("use-xml-tags"));
                            appendSystemMessage("No file operation tags found — no files were changed. " +
                                    "A correction has been injected. Please ask again.");
                            // Always inject — covers plain-text responses AND post-retry failures
                            history.add(new ChatMessage("user",
                                    "CORRECTION REQUIRED: Your last response still did not write any files. " +
                                    "You MUST output a raw XML tag with complete code inside it. " +
                                    "Use the detected language and the appropriate test framework or file conventions. " +
                                    "Output ONLY the XML tag — no ``` fences, no explanation before it."));
                            history.add(new ChatMessage("assistant",
                                    "Understood. I will output only the raw XML tag with complete code inside it."));
                        }
                    } else {
                        // Not in EDITING mode
                        boolean hasFileOps = fullResponse.contains("<CREATE_FILE") ||
                                             fullResponse.contains("<MODIFY_FILE") ||
                                             fullResponse.contains("<CREATE_FOLDER") ||
                                             fullResponse.contains("<DELETE_FILE") ||
                                             fullResponse.contains("<DELETE_FOLDER");

                        if (ChatPanelSupport.isProjectStructureIntent(userText)
                                && (ChatPanelSupport.isNonActionableModelResponse(fullResponse)
                                    || (fullResponse.contains("<EXECUTE_COMMAND")
                                        && ChatPanelSupport.isStructureListingCommand(fullResponse)))) {
                            appendSystemMessage("Project structure requests are rendered directly. Ignoring shell listing output.");
                            String structure = ChatPanelSupport.stripProjectStructureWrappers(
                                    ChatPanelSupport.formatProjectStructureResponse(project));
                            if (!structure.isBlank()) {
                                history.add(new ChatMessage("assistant", structure));
                            }
                            refreshTelemetry("Idle", finalSnapshot);
                            setLoading(false);
                            return;
                        }

                        if (fullResponse.contains("<RUN_TESTS") || fullResponse.contains("<CHECK_COMPILATION") || fullResponse.contains("<EXECUTE_COMMAND")) {
                            FileOperationUtil.FileOpResult opResult = FileOperationUtil.processFileOperations(project, fullResponse);
                            if (opResult.warnings != null && !opResult.warnings.isEmpty()) {
                                opResult.warnings.forEach(this::appendSystemMessage);
                                injectBlockedWriteFeedback(opResult.warnings);
                            }
                            recordMistakes(opResult.mistakeKeys);
                            refreshVersionControlStatus();
                            if (opResult.runTests) {
                                appendSystemMessage("File operations applied. Running build check before tests…");
                                refreshTelemetry("Testing", finalSnapshot);
                                scheduleBuildCheck(model, endpoint, taskType, true, opResult.testName);
                                return;
                            } else if (opResult.checkCompilation) {
                                appendSystemMessage("Compilation check requested. Running build…");
                                refreshTelemetry("Debugging", finalSnapshot);
                                scheduleBuildCheck(model, endpoint, taskType, false, null);
                                return;
                            } else if (opResult.customCommand != null) {
                                appendSystemMessage("Custom command execution requested: " + opResult.customCommand + ". Running…");
                                refreshTelemetry("Running command", finalSnapshot);
                                scheduleCustomCommand(opResult.customCommand);
                                return;
                            }
                        }

                        if (fullResponse.contains("<GIT_ADD_NEW")) {
                            scheduleGitAdd();
                            return;
                        }

                        if ("PLANNING".equals(mode)) {
                            if (hasFileOps) {
                                appendSystemMessage("⚠ File operation tags detected but skipped because you are in PLANNING mode. Switch to EDITING mode to allow changes.");
                            } else if (ChatPanelSupport.isFileOpIntent(userText)) {
                                appendSystemMessage("💡 It looks like you want to make changes. Please switch to EDITING mode and ask again to have the files written to disk.");
                            }
                        }
                    }

                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    boolean isStop = "STOPPED_BY_USER".equals(ex.getMessage()) ||
                                     "STREAM_INTERRUPTED".equals(ex.getMessage()) ||
                                     ex instanceof InterruptedException ||
                                     (ex.getCause() instanceof InterruptedException);

                    if (isStop || stopRequested) {
                        history.add(new ChatMessage("assistant", assistantBuffer.toString() + " [Interrupted]"));
                        // Already showed "Interrupted by user." via stop button listener
                    } else {
                        appendSystemMessage("Error: " + ex.getMessage());
                    }
                    refreshTelemetry(isStop || stopRequested ? "Interrupted" : "Debugging", finalSnapshot);
                    setLoading(false);
                });
            }
        });
        currentChatThread.setDaemon(true);
        currentChatThread.start();
    }

    // -------------------------------------------------------------------------
    // Document helpers — EDT only
    // -------------------------------------------------------------------------

    private void appendUserMessage(String text) {
        insert("You\n", userRoleStyle);
        insert(text + "\n\n", userTextStyle);
    }

    private void recordPromptHistory(String text) {
        List<String> updated = ChatPanelSupport.recordPromptHistory(promptHistory, text, MAX_PROMPT_HISTORY);
        promptHistory.clear();
        promptHistory.addAll(updated);
        resetPromptHistoryNavigation();
    }

    private void resetPromptHistoryNavigation() {
        promptHistoryIndex = -1;
        promptHistoryDraft = "";
    }

    private boolean shouldUsePromptHistoryNavigation(int keyCode) {
        if (promptHistory.isEmpty()) return false;
        if (promptHistoryIndex != -1) return true;
        if (promptArea.getText().isBlank()) return keyCode == KeyEvent.VK_UP;
        int caretPosition = promptArea.getCaretPosition();
        if (keyCode == KeyEvent.VK_UP) {
            return caretPosition == 0;
        }
        return caretPosition == promptArea.getText().length();
    }

    private boolean shouldResetPromptHistoryNavigation(KeyEvent e) {
        int code = e.getKeyCode();
        return code != KeyEvent.VK_SHIFT
                && code != KeyEvent.VK_CONTROL
                && code != KeyEvent.VK_ALT
                && code != KeyEvent.VK_META
                && code != KeyEvent.VK_UP
                && code != KeyEvent.VK_DOWN
                && code != KeyEvent.VK_ENTER
                && !e.isActionKey();
    }

    private void appendAssistantMessage(String text) {
        insert("Assistant\n", assistantRoleStyle);
        insert(text + "\n\n", assistantTextStyle);
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void beginAssistantMessage() {
        streaming = true;
        cursorOn  = false;
        assistantBuffer.setLength(0);
        insert("Assistant\n", assistantRoleStyle);
    }

    private void appendToken(String token) {
        assistantBuffer.append(token);
        estimatedOutputTokens += ChatPanelSupport.estimateTokens(token);
        refreshWorkspaceStatus();
        removeCursorIfPresent();
        insert(token, assistantTextStyle);
        insert("▌", cursorStyle);
        cursorOn = true;
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void finalizeAssistantMessage() {
        streaming = false;
        blinkTimer.stop();
        removeCursorIfPresent();
        insert("\n\n", assistantTextStyle);
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void toggleBlink() {
        if (!streaming) return;
        try {
            int end = chatDoc.getLength();
            if (cursorOn) {
                if (end > 0 && chatDoc.getText(end - 1, 1).equals("▌")) {
                    chatDoc.remove(end - 1, 1);
                }
                cursorOn = false;
            } else {
                if (end == 0 || !chatDoc.getText(end - 1, 1).equals("▌")) {
                    chatDoc.insertString(end, "▌", cursorStyle);
                }
                cursorOn = true;
            }
        } catch (BadLocationException ignored) {}
    }

    private void appendSystemMessage(String text) {
        insert(text + "\n\n", systemStyle);
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void insert(String text, Style style) {
        try {
            chatDoc.insertString(chatDoc.getLength(), text, style);
        } catch (BadLocationException ignored) {}
    }

    private void removeCursorIfPresent() {
        try {
            int end = chatDoc.getLength();
            if (end > 0 && chatDoc.getText(end - 1, 1).equals("▌")) {
                chatDoc.remove(end - 1, 1);
            }
        } catch (BadLocationException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void scheduleBuildCheck(String model, String endpoint, AgentTask.TaskType taskType,
                                    boolean runTestsAfterBuild, String testNameAfterBuild) {
        refreshTelemetry("Debugging", null);
        // Runs after all VFS write actions have been dispatched to the EDT queue
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runCompile(project);
                String projectType = ChatPanelSupport.projectTypeLabel(project);
                // Scan for source files WHILE still in daemon thread — never on EDT
                java.util.List<String> brokenPaths = result.success()
                        ? java.util.Collections.emptyList()
                        : ChatPanelSupport.extractBrokenFilePaths(result.output());
                String sourceContext = result.success() ? "" : ChatPanelSupport.scanProjectForErrorContext(project, result.output(), brokenPaths);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Build successful.");
                        lastCompileFingerprint = "";
                        // If we were fixing a test failure, re-run the tests now
                        if (inTestFixLoop) {
                            inTestFixLoop = false;
                            appendSystemMessage("Compile passed after test fix — re-running tests…");
                            scheduleTestRun(model, endpoint, testFixName, taskType);
                        } else if (runTestsAfterBuild) {
                            appendSystemMessage("Build passed — running tests…");
                            scheduleTestRun(model, endpoint, testNameAfterBuild, taskType);
                        } else {
                            setLoading(false);
                        }
                    } else if (buildFixAttempts < AutoFixLoop.MAX_COMPILE_ATTEMPTS) {
                        buildFixAttempts++;
                        String errors = result.output();

                        // Fingerprint to detect stuck loop
                        String fp = AutoFixLoop.extractCompileFingerprint(errors);
                        boolean sameError = AutoFixLoop.isSameError(lastCompileFingerprint, fp);
                        lastCompileFingerprint = fp;

                        String pathHint = brokenPaths.isEmpty() ? "" :
                                "\nUse EXACTLY this tag:\n" +
                                "<MODIFY_FILE path=\"" + brokenPaths.get(0) + "\">\n" +
                                "// complete corrected file content\n" +
                                "</MODIFY_FILE>";

                        String fixInstruction = PromptBuilder.buildCompileFixPrompt(
                                errors, pathHint, sourceContext, projectType, buildFixAttempts, sameError);

                        appendSystemMessage("⚠ Build errors — asking LLM to fix " +
                                "(attempt " + buildFixAttempts + "/" +
                                AutoFixLoop.MAX_COMPILE_ATTEMPTS + ")…");
                        history.add(new ChatMessage("user", fixInstruction));
                        beginAssistantMessage();
                        blinkTimer.start();
                        streamAndHandle(model, endpoint, null, false, taskType);
                    } else {
                        String errors = result.output();
                        if (errors.length() > 3000) errors = errors.substring(0, 3000) + "\n[...truncated]";
                        appendSystemMessage("❌ Build failed after " +
                                AutoFixLoop.MAX_COMPILE_ATTEMPTS + " attempts:\n" + errors);
                        setLoading(false);
                    }
                });
            })
        );
    }

    private void scheduleTestRun(String model, String endpoint, String testName, AgentTask.TaskType taskType) {
        refreshTelemetry("Testing", null);
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                SwingUtilities.invokeLater(() -> appendSystemMessage("Test runner started…"));
                BuildUtil.BuildResult result = BuildUtil.runTest(project, testName);
                String projectType = ChatPanelSupport.projectTypeLabel(project);
                // Scan for source files WHILE still in daemon thread — never on EDT
                java.util.List<String> brokenPaths = result.success()
                        ? java.util.Collections.emptyList()
                        : ChatPanelSupport.extractBrokenFilePaths(result.output());
                String sourceContext = result.success() ? "" : ChatPanelSupport.scanProjectForErrorContext(project, result.output(), brokenPaths);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Tests passed successfully.");
                        setLoading(false);
                    } else {
                        appendSystemMessage("❌ Tests failed.");

                        StringBuilder output = new StringBuilder();
                        if (result.testResults() != null && !result.testResults().isEmpty()) {
                            output.append("\nTest Results:\n");
                            output.append(String.format("%-40s | %-10s\n", "Test Name", "Status"));
                            output.append("-".repeat(41)).append("|").append("-".repeat(11)).append("\n");
                            for (var tr : result.testResults()) {
                                output.append(String.format("%-40s | %-10s\n",
                                        tr.name().length() > 40 ? tr.name().substring(0, 37) + "..." : tr.name(),
                                        tr.status()));
                            }
                        }
                        String rawOutput = result.output();
                        if (!rawOutput.isBlank()) output.append("\n").append(rawOutput);
                        appendSystemMessage(ChatPanelSupport.stripProjectStructureWrappers(output.toString()));

                        if (buildFixAttempts < AutoFixLoop.MAX_TEST_ATTEMPTS) {
                            buildFixAttempts++;
                            String errors = rawOutput;

                            // Fingerprint to detect stuck loop
                            String fp = AutoFixLoop.extractTestFingerprint(errors);
                            boolean sameError = AutoFixLoop.isSameError(lastTestFingerprint, fp);
                            lastTestFingerprint = fp;

                            String pathHint = brokenPaths.isEmpty() ? "" :
                                    "\nUse EXACTLY this tag:\n" +
                                    "<MODIFY_FILE path=\"" + brokenPaths.get(0) + "\">\n" +
                                    "// complete corrected file content\n" +
                                    "</MODIFY_FILE>";

                            String fixInstruction = PromptBuilder.buildTestFixPrompt(
                                    errors, pathHint, sourceContext, projectType, buildFixAttempts, sameError);

                            // Set flag so that after the LLM writes a fix and it compiles,
                            // scheduleBuildCheck will re-run the tests automatically
                            inTestFixLoop = true;
                            testFixName   = testName;

                            appendSystemMessage("⚠ Test failures — asking LLM to fix " +
                                    "(attempt " + buildFixAttempts + "/" +
                                    AutoFixLoop.MAX_TEST_ATTEMPTS + ")…");
                            history.add(new ChatMessage("user", fixInstruction));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false, taskType);
                        } else {
                            inTestFixLoop = false;
                            appendSystemMessage("❌ Tests still failing after " +
                                    AutoFixLoop.MAX_TEST_ATTEMPTS + " attempts.");
                            setLoading(false);
                        }
                    }
                });
            })
        );
    }

    /**
     * Extracts relative file paths (e.g. "src/test/java/plugin/FooTest.java") from Maven error output.
     * Works with both forward-slash and back-slash separators.
     */
    private static java.util.List<String> extractBrokenFilePaths(String errorOutput) {
        String normalised = errorOutput.replace("\\", "/");
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        // Match Java file paths followed by :[line,col] markers
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[^\\s]+\\.java(?::\\[?\\d[^\\s]*)?").matcher(normalised);
        while (m.find()) {
            String hit = m.group(0).replaceAll(":\\[?\\d.*", ""); // strip :[line,col]
            int srcIdx = hit.indexOf("/src/");
            if (srcIdx >= 0) {
                paths.add(hit.substring(srcIdx + 1)); // "src/test/java/plugin/Foo.java"
            }
        }
        return new java.util.ArrayList<>(paths);
    }

    /**
     * Returns true when the error is a simple syntax mistake (missing brace, semicolon, etc.)
     * that the LLM should fix by patching the file, not by studying the source API.
     */
    private static boolean isSimpleSyntaxError(String errorOutput) {
        String lower = errorOutput.toLowerCase();
        return lower.contains("reached end of file") ||
               lower.contains("';' expected")         ||
               lower.contains("'(' expected")         ||
               lower.contains("')' expected")         ||
               lower.contains("'{' expected")         ||
               lower.contains("'}' expected")         ||
               lower.contains("illegal start of expression") ||
               lower.contains("class, interface, or enum expected") ||
               lower.contains("not a statement");
    }

    /**
     * Scans src/main/java for source files whose class names appear in the error output,
     * and ALWAYS includes the failing test file itself.
     * Called off the EDT (daemon thread only).
     *
     * @param knownBrokenPaths pre-extracted relative paths from extractBrokenFilePaths()
     */
    private String scanProjectForErrorContext(String errorOutput,
                                              java.util.List<String> knownBrokenPaths) {
        // Normalise separators — Maven on Windows can emit either \ or /
        String normalised = errorOutput.replace("\\", "/");

        java.util.Set<String> classNames = new java.util.LinkedHashSet<>();

        // "cannot find symbol: class Foo" → Foo
        java.util.regex.Matcher symbolMatcher =
                java.util.regex.Pattern.compile("symbol:\\s+class\\s+(\\w+)").matcher(normalised);
        while (symbolMatcher.find()) classNames.add(symbolMatcher.group(1));

        // "location: class plugin.x.ClassName" → ClassName
        java.util.regex.Matcher locationMatcher =
                java.util.regex.Pattern.compile("location:.*?(\\w+)$", java.util.regex.Pattern.MULTILINE)
                        .matcher(normalised);
        while (locationMatcher.find()) classNames.add(locationMatcher.group(1));

        // "/path/FooTest.java:[line,col]" → strip optional Test suffix → source class Foo
        java.util.regex.Matcher fileInErrorMatcher =
                java.util.regex.Pattern.compile("/(\\w+?)(?:Test)?\\.java:\\[?\\d").matcher(normalised);
        while (fileInErrorMatcher.find()) classNames.add(fileInErrorMatcher.group(1));

        String basePath = project.getBasePath();
        if (basePath == null) return "";

        StringBuilder context = new StringBuilder();

        // For API/semantic errors: scan main sources
        if (!classNames.isEmpty()) {
            java.nio.file.Path srcMain = java.nio.file.Paths.get(basePath, "src", "main", "java");
            for (String className : classNames) {
                try {
                    java.util.Optional<java.nio.file.Path> found = java.nio.file.Files.walk(srcMain)
                            .filter(p -> p.getFileName().toString().equals(className + ".java"))
                            .findFirst();
                    if (found.isPresent()) {
                        String content = java.nio.file.Files.readString(found.get(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        context.append("=== ").append(className).append(".java ===\n")
                               .append(content).append("\n\n");
                    }
                } catch (Exception ignored) {}
            }
        }

        // ALWAYS include the failing test file(s) — critical for syntax error fixes.
        // Use pre-extracted paths when available (more reliable than regex on Windows paths).
        java.util.Set<String> testFilesToRead = new java.util.LinkedHashSet<>();
        for (String rel : knownBrokenPaths) {
            if (rel.endsWith("Test.java") || rel.contains("test/")) {
                testFilesToRead.add(rel);
            }
        }
        // Fallback to regex only when no paths were extracted
        if (testFilesToRead.isEmpty()) {
            java.util.regex.Matcher tf =
                    java.util.regex.Pattern.compile("/(\\w+Test)\\.java").matcher(normalised);
            java.util.Set<String> testClassNames = new java.util.LinkedHashSet<>();
            while (tf.find()) testClassNames.add(tf.group(1));
            java.nio.file.Path srcTest = java.nio.file.Paths.get(basePath, "src", "test", "java");
            for (String testClass : testClassNames) {
                try {
                    java.util.Optional<java.nio.file.Path> found = java.nio.file.Files.walk(srcTest)
                            .filter(p -> p.getFileName().toString().equals(testClass + ".java"))
                            .findFirst();
                    found.ifPresent(p -> testFilesToRead.add(
                            java.nio.file.Paths.get(basePath).relativize(p).toString().replace("\\", "/")));
                } catch (Exception ignored) {}
            }
        }

        for (String relPath : testFilesToRead) {
            try {
                java.nio.file.Path abs = java.nio.file.Paths.get(basePath, relPath.replace("/", java.io.File.separator));
                if (java.nio.file.Files.exists(abs)) {
                    String content = java.nio.file.Files.readString(abs, java.nio.charset.StandardCharsets.UTF_8);
                    String label = java.nio.file.Paths.get(relPath).getFileName().toString();
                    context.append("=== ").append(label).append(" (current content — fix this file) ===\n")
                           .append(content).append("\n\n");
                }
            } catch (Exception ignored) {}
        }

        return context.toString();
    }

    private void scheduleGitAdd() {
        refreshTelemetry("Version control", null);
        if (newlyCreatedFiles.isEmpty()) {
            appendSystemMessage("No new files to add to Git.");
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                GitUtil.GitResult result = GitUtil.addFiles(project, newlyCreatedFiles);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Successfully added " + newlyCreatedFiles.size() + " new file(s) to Git.");
                        refreshVersionControlStatus();
                    } else {
                        appendSystemMessage("❌ Git add failed:\n" + result.output());
                    }
                });
            })
        );
    }

    private void scheduleCustomCommand(String command) {
        refreshTelemetry("Running command", null);
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runCustomCommand(project, command);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Command executed successfully:\n" + result.output());
                    } else {
                        appendSystemMessage("❌ Command failed:\n" + result.output());
                    }
                    setLoading(false);
                });
            })
        );
    }

    private static boolean isFileOpIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("edit") || lower.contains("modify") || lower.contains("update") ||
               lower.contains("create") || lower.contains("delete") || lower.contains("change") ||
               lower.contains("write") || lower.contains("fix") || lower.contains("remove") ||
               lower.contains("rename") || lower.contains("replace") || lower.contains("refactor") ||
               lower.contains("implement") ||
               lower.contains("run test") || lower.contains("execute test") || lower.contains("check test") ||
               lower.contains("add test") || lower.contains("missing test") || lower.contains("test case") ||
               (lower.contains("add") && (lower.contains("file") || lower.contains("class") || lower.contains("method")));
    }

    public void setTabContent(Content content) {
        this.tabContent = content;
    }

    private void generateTitle(String firstUserMessage) {
        PluginSettings s = PluginSettings.getInstance();
        String model    = s.getModel();
        String endpoint = s.getEndpoint();
        if (model == null || model.isBlank()) return;

        String excerpt = firstUserMessage.substring(0, Math.min(200, firstUserMessage.length()));
        List<ChatMessage> req = List.of(new ChatMessage("user",
                "Give a very short title (3-5 words) for a chat conversation that starts with: \""
                + excerpt + "\". Reply with ONLY the title, no quotes, no explanation."));

        daemon(() -> {
            StringBuilder buf = new StringBuilder();
            try {
                new LocalLLMClient(endpoint).streamChat(model, req, buf::append);
                String title = buf.toString().trim().replaceAll("^[\"']+|[\"']+$", "");
                if (!title.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        titleLabel.setText("  " + title);
                        if (tabContent != null) tabContent.setDisplayName(title);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void setLoading(boolean loading) {
        isGenerating = loading;

        sendBtn.setEnabled(!loading);
        stopBtn.setVisible(loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
        promptArea.setEnabled(!loading);
        refreshTelemetry(loading ? "Working" : "Ready", null);
        if (!loading) {
            promptArea.requestFocusInWindow();
            showDoneNotification();
        }
    }

    private void showDoneNotification() {
        SwingUtilities.invokeLater(() -> {
            try {
                JWindow toast = new JWindow();
                JPanel panel = new JPanel();
                panel.setBorder(new javax.swing.border.LineBorder(new java.awt.Color(0,0,0,120), 1, true));
                panel.setBackground(new java.awt.Color(30, 30, 30, 230));
                panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
                JLabel label = new JLabel("  Local LLM: Task completed.  ");
                label.setForeground(new java.awt.Color(240, 240, 240));
                panel.add(label);
                toast.add(panel);
                toast.pack();
                java.awt.Point base = titleLabel.isShowing() ? titleLabel.getLocationOnScreen() : root.getLocationOnScreen();
                int x = base.x + 10;
                int y = Math.max(0, base.y - toast.getHeight() - 10);
                toast.setLocation(x, y);
                toast.setAlwaysOnTop(true);
                toast.setVisible(true);
                javax.swing.Timer timer = new javax.swing.Timer(2500, e -> toast.dispose());
                timer.setRepeats(false);
                timer.start();
            } catch (Throwable ignored) {
                // Last resort: do nothing if even Swing is unavailable
            }
        });
    }

    private void clearConversation() {
        history.clear();
        newlyCreatedFiles.clear();
        pendingAttachments.clear();
        estimatedInputTokens = 0;
        estimatedOutputTokens = 0;
        currentContextUsagePercent = 0;
        streaming = false;
        cursorOn = false;
        assistantBuffer.setLength(0);
        blinkTimer.stop();
        chatDoc = new DefaultStyledDocument();
        initStyles();
        chatPane.setStyledDocument(chatDoc);
        titleGenerated = false;
        buildFixAttempts = 0;
        titleLabel.setText("  New Chat");
        if (tabContent != null) tabContent.setDisplayName("New Chat");
        refreshWorkspaceStatus();
        refreshTelemetry("Ready", null);
        refreshVersionControlStatus();
        refreshAttachmentStrip();
        appendSystemMessage("Chat cleared. New conversation started.");
        promptArea.requestFocusInWindow();
        lastCompileFingerprint = "";
        lastTestFingerprint    = "";
        inTestFixLoop          = false;
        testFixName            = null;
        // Refresh RAG index so new/modified files are picked up
        daemon(() -> {
            if (contextCollector != null) contextCollector.reindex();
        });
    }

    @Override
    public void dispose() {
        stopRequested = true;
        if (currentChatThread != null && currentChatThread.isAlive()) {
            currentChatThread.interrupt();
        }
        blinkTimer.stop();
        statusPulseTimer.stop();
        if (contextCollector != null) {
            contextCollector = null;
        }
        skillMemory = null;
        history.clear();
        promptHistory.clear();
        newlyCreatedFiles.clear();
        pendingAttachments.clear();
        project.putUserData(PANEL_KEY, null);
    }

    private void refreshWorkspaceStatus() {
        if (workspaceStatusLabel == null) return;
        workspaceStatusLabel.setText(ChatPanelSupport.formatWorkspaceStatus(
                workspaceProjectTypeLabel, estimatedInputTokens, estimatedOutputTokens));
        workspaceStatusLabel.setToolTipText(workspaceStatusLabel.getText());
    }

    private void refreshTelemetry(String activity, List<ChatMessage> snapshot) {
        if (activityLabel != null) {
            String displayActivity = ChatPanelSupport.canonicalActivityPhase(activity);
            if ("PLANNING".equals(mode) && "Thinking".equals(displayActivity)) {
                displayActivity = "Planning";
            }
            if (!displayActivity.equals(lastAnnouncedTelemetryActivity)) {
                String announcement = ChatPanelSupport.telemetryAnnouncement(displayActivity);
                if (!announcement.isBlank() && !"Ready.".equals(announcement)) {
                    appendSystemMessage(announcement);
                }
                lastAnnouncedTelemetryActivity = displayActivity;
            }
            statusBaseActivity = displayActivity;
            updatePhaseStrip(displayActivity);
            boolean animate = !"Ready".equals(displayActivity)
                    && !"Idle".equals(displayActivity)
                    && !"Interrupted".equals(displayActivity);
            if (animate) {
                if (!statusPulseTimer.isRunning()) {
                    statusPulsePhase = 0;
                    statusPulseTimer.start();
                }
                updateActivityLabel();
            } else {
                statusPulseTimer.stop();
                activityLabel.setText(displayActivity);
            }
            activityLabel.setToolTipText(activityLabel.getText());
        }
        if (fileHistoryLabel != null) {
            fileHistoryLabel.setText("Files: " + newlyCreatedFiles.size() + " new");
            fileHistoryLabel.setToolTipText(fileHistoryLabel.getText());
        }
        if (contextBar != null) {
            List<ChatMessage> source = snapshot == null ? history : snapshot;
            currentContextUsagePercent = ChatPanelSupport.contextUsagePercent(source, CONTEXT_BUDGET_TOKENS);
            contextBar.setValue(currentContextUsagePercent);
            contextBar.setString("Context " + currentContextUsagePercent + "%");
            contextBar.setToolTipText("Estimated context usage: " + currentContextUsagePercent + "% of ~" + CONTEXT_BUDGET_TOKENS + " tokens.");
        }
    }

    private void toggleStatusPulse() {
        updateActivityLabel();
    }

    private void updateActivityLabel() {
        if (activityLabel == null) return;
        String[] suffixes = {"", ".", "..", "..."};
        String suffix = suffixes[Math.floorMod(statusPulsePhase, suffixes.length)];
        activityLabel.setText(statusBaseActivity + suffix);
        activityLabel.setToolTipText(activityLabel.getText());
        statusPulsePhase = (statusPulsePhase + 1) % suffixes.length;
        updatePhaseStrip(statusBaseActivity);
    }

    private void updatePhaseStrip(String activePhase) {
        if (phaseChipLabels.isEmpty()) return;
        String canonical = ChatPanelSupport.canonicalActivityPhase(activePhase);
        for (Map.Entry<String, JLabel> entry : phaseChipLabels.entrySet()) {
            boolean active = entry.getKey().equals(canonical);
            JLabel chip = entry.getValue();
            chip.setForeground(active ? UIManager.getColor("Label.foreground") : UIManager.getColor("Label.disabledForeground"));
            chip.setBackground(active ? new Color(0x2D7DD2) : UIManager.getColor("Panel.background"));
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(active ? new Color(0x4C9AFF) : UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)
            ));
            String baseText = entry.getKey();
            chip.setText(active ? baseText + suffixForPulse() : baseText);
            chip.setToolTipText(active ? baseText + " (active)" : baseText);
        }
    }

    private String suffixForPulse() {
        return switch (statusPulsePhase % 4) {
            case 1 -> ".";
            case 2 -> "..";
            case 3 -> "...";
            default -> "";
        };
    }

    private void refreshVersionControlStatus() {
        if (gitStatusLabel == null) return;
        gitStatusLabel.setText("Git: checking…");
        daemon(() -> {
            GitUtil.GitResult result = GitUtil.status(project);
            SwingUtilities.invokeLater(() -> {
                if (gitStatusLabel == null) return;
                if (result.success()) {
                    String output = result.output();
                    String summary;
                    if (output == null || output.isBlank()) {
                        summary = "Git: clean";
                    } else {
                        long changed = output.lines().filter(line -> !line.startsWith("##") && !line.isBlank()).count();
                        summary = "Git: " + changed + " change(s)";
                    }
                    gitStatusLabel.setText(summary);
                    gitStatusLabel.setToolTipText(result.output());
                } else {
                    gitStatusLabel.setText("Git: unavailable");
                    gitStatusLabel.setToolTipText(result.output());
                }
            });
        });
    }

    private String buildMemoryContext(String query, List<AttachmentData> attachments) {
        SkillMemory memory = getSkillMemory();
        StringBuilder sb = new StringBuilder();
        if (memory != null) {
            String memorySummary = memory.buildContextSummary(query);
            if (!memorySummary.isBlank()) {
                sb.append(memorySummary.strip());
            }
        }
        String attachmentSummary = AttachmentUtil.buildTaskHint(attachments);
        if (!attachmentSummary.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("# Attachment Intent\n- ").append(attachmentSummary);
        }
        String containerSummary = ChatPanelSupport.buildContainerContext(project);
        if (!containerSummary.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(containerSummary.strip());
        }
        return sb.toString();
    }

    private String buildReviewContext(String query, List<AttachmentData> attachments) {
        if (!ChatPanelSupport.isCommitReviewIntent(query)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        GitTool gitTool = new GitTool(project);
        GitTool.ToolResult branch = gitTool.currentBranch();
        GitTool.ToolResult lastCommit = gitTool.lastCommit();
        if (branch != null && branch.success() && branch.output() != null && !branch.output().isBlank()) {
            sb.append("Current branch: ").append(branch.output().trim()).append("\n");
        }
        if (lastCommit != null && lastCommit.success() && lastCommit.output() != null && !lastCommit.output().isBlank()) {
            sb.append("Latest commit details:\n").append(lastCommit.output().trim()).append("\n");
        }
        String attachmentBlock = AttachmentUtil.buildPromptBlock(attachments);
        if (!attachmentBlock.isBlank()) {
            sb.append("Attached ticket/spec context:\n").append(attachmentBlock.trim()).append("\n");
        }
        if (query != null && !query.isBlank()) {
            sb.append("Review request:\n").append(query.trim()).append("\n");
        }
        sb.append("""
                Review rules:
                - Compare the last commit against the ticket/spec and the current codebase.
                - Identify missing scope, regressions, and incomplete tests.
                - Write actionable reviewer comments with file and line references where possible.
                - If the commit is acceptable, say what was verified and why.
                - If GitLab review publishing is requested, keep comments concise and specific.
                """.trim());
        return sb.toString();
    }

    private void maybePublishGitLabReview(String userText, String reviewText) {
        if (!ChatPanelSupport.isCommitReviewIntent(userText)) {
            return;
        }
        PluginSettings settings = PluginSettings.getInstance();
        if (settings == null) {
            return;
        }
        if (settings.getGitlabProject() == null || settings.getGitlabProject().isBlank()) {
            return;
        }
        if (settings.getGitlabApiUrl() == null || settings.getGitlabApiUrl().isBlank()) {
            return;
        }
        if (settings.getGitlabToken() == null || settings.getGitlabToken().isBlank()) {
            return;
        }

        daemon(() -> {
            GitTool gitTool = new GitTool(project);
            GitTool.ToolResult shaResult = gitTool.currentCommitSha();
            if (shaResult == null || !shaResult.success() || shaResult.output() == null || shaResult.output().isBlank()) {
                SwingUtilities.invokeLater(() ->
                        appendSystemMessage("GitLab review comment not posted: could not resolve the current commit SHA."));
                return;
            }

            String note = reviewText == null ? "" : reviewText.trim();
            if (note.length() > 5000) {
                note = note.substring(0, 5000) + "\n[...truncated]";
            }

            IntegrationAccessUtil.IntegrationTestResult result =
                    IntegrationAccessUtil.postGitLabCommitComment(settings, shaResult.output().trim(), note);
            String status = result.success()
                    ? "Posted review comment to GitLab commit " + shaResult.output().trim()
                    : "GitLab review comment failed: " + result.message();
            SwingUtilities.invokeLater(() -> appendSystemMessage(status));
        });
    }

    private SkillMemory getSkillMemory() {
        if (skillMemory == null) {
            skillMemory = new SkillMemory(project);
        }
        return skillMemory;
    }

    private boolean shouldRememberSkill(String text, List<AttachmentData> attachments) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("remember this")
                || lower.contains("save this as skill")
                || lower.contains("save it in your memory")
                || lower.contains("learn this")
                || lower.contains("store this")
                || (lower.contains("remember") && AttachmentUtil.containsJiraTicketAttachment(attachments));
    }

    private void rememberSkillFromPrompt(String text, List<AttachmentData> attachments) {
        SkillMemory memory = getSkillMemory();
        String key = deriveSkillName(text, attachments);
        String value = AttachmentUtil.buildPromptBlock(attachments);
        if (value.isBlank()) {
            value = text;
        } else {
            value = text + "\n\n" + value;
        }
        memory.remember(key, value);
        appendSystemMessage("Saved to skill memory: " + key);
    }

    private void maybeUpdateSkillFromSession(String userText, String assistantText, List<AttachmentData> attachments) {
        if (!ChatPanelSupport.isSkillUpdateIntent(userText)) {
            return;
        }
        SkillMemory memory = getSkillMemory();
        String key = deriveSkillName(userText, attachments);
        String attachmentBlock = AttachmentUtil.buildPromptBlock(attachments);
        String sessionText = assistantText == null ? "" : assistantText.trim();
        if (!attachmentBlock.isBlank()) {
            sessionText = attachmentBlock + "\n\n" + sessionText;
        }
        memory.rememberFromSession(key, userText, sessionText);
        appendSystemMessage("Updated skill memory: " + key);
    }

    private String deriveSkillName(String text, List<AttachmentData> attachments) {
        if (AttachmentUtil.containsPatchAttachment(attachments)) return "patch-workflow";
        if (AttachmentUtil.containsJiraTicketAttachment(attachments)) return "jira-ticket-workflow";
        if (AttachmentUtil.containsPdfAttachment(attachments)) return "pdf-spec-workflow";
        if (AttachmentUtil.containsImageAttachment(attachments)) return "image-spec-workflow";
        if (text != null) {
            String lower = text.toLowerCase();
            if (lower.contains("triple f") || lower.contains("triple-f")) return "triple-f-pattern";
            if (lower.contains("test case")) return "test-case-pattern";
            if (lower.contains("readme")) return "readme-workflow";
            if (lower.contains("docker")) return "docker-workflow";
            if (lower.contains("helm")) return "helm-workflow";
        }
        if (text == null || text.isBlank()) return "general-task";
        String cleaned = text.replaceAll("[^a-zA-Z0-9 ]", " ").trim().toLowerCase();
        if (cleaned.isBlank()) return "general-task";
        String[] parts = cleaned.split("\\s+");
        return parts[0] + (parts.length > 1 ? "-" + parts[1] : "");
    }

    private String buildTaskRetryCorrection(String userText, AgentTask.TaskType taskType, List<AttachmentData> attachments) {
        if (ChatPanelSupport.isReadmeIntent(userText) || taskType == AgentTask.TaskType.DOCUMENT) {
            return """
                    CORRECTION REQUIRED: Create or update README.md for the current repository using the current workspace context.
                    Do not ask for the repository path.
                    Do not output a shell command or a clarifying question.
                    Produce the concrete README content now with overview, technologies, features, setup, run, test, environment requirements, and deployment/container notes.
                    """.trim();
        }
        if (ChatPanelSupport.isCommitReviewIntent(userText) || taskType == AgentTask.TaskType.REVIEW_COMMIT) {
            return """
                    CORRECTION REQUIRED: Review the latest commit against the current branch and any ticket/spec context already available.
                    Do not ask for a repository path or emit a shell command.
                    Return actionable reviewer comments with missing behavior, regressions, and test gaps. Prefer file and line references.
                    """.trim();
        }
        if (ChatPanelSupport.isSkillUpdateIntent(userText)) {
            return """
                    CORRECTION REQUIRED: Persist the solved workflow as reusable skill memory.
                    Do not ask for more context or output a shell command.
                    Save the learned pattern from this session and summarize what was learned.
                    """.trim();
        }
        if (ChatPanelSupport.isDockerOrHelmIntent(userText)) {
            return """
                    CORRECTION REQUIRED: Inspect the current repository's Docker, Compose, or Helm files using the workspace context already available.
                    Do not ask for the repository path or output a shell command.
                    Explain what the container configuration does, or apply the requested container change directly.
                    """.trim();
        }
        if (ChatPanelSupport.isAnalysisIntent(userText)) {
            return """
                    CORRECTION REQUIRED: Analyze the current repository using the workspace context already available.
                    Do not ask for the repository path or output a shell command.
                    Return a structured analysis with concrete repository findings for the requested topic.
                    Include the relevant files, dependencies, build system, framework, risks, and suggested follow-up actions when applicable.
                    """.trim();
        }
        if (taskType == AgentTask.TaskType.FIX_BUG) {
            return """
                    CORRECTION REQUIRED: Fix the compilation/build problem in the current repository using the workspace context already available.
                    Do not ask for the repository path or output a shell command.
                    Identify the failing files, apply the minimal fix, rebuild, and continue until the project compiles successfully.
                    """.trim();
        }
        if (taskType == AgentTask.TaskType.GENERATE_TESTS || ChatPanelSupport.isFileOpIntent(userText)) {
            String targetNote = AttachmentUtil.containsJiraTicketAttachment(attachments)
                    ? "Use the ticket/spec context if attached."
                    : "Use the real repository class, package, and framework conventions.";
            return """
                    CORRECTION REQUIRED: Write the actual test file or file operation for the current repository.
                    Do not output a shell command, generic example, test plan, or clarifying question.
                    Use the project's detected language and test framework. If the package folders are missing, create them in the file-operation path. %s
                    Output only the required XML file-operation tag with complete content.
                    """.formatted(targetNote).trim();
        }
        return """
                CORRECTION REQUIRED: Answer the current repository task directly using the current workspace context.
                Do not output a shell command or a clarifying question.
                """.trim();
    }

    private void refreshAttachmentStrip() {
        if (attachmentCardsPanel == null) return;
        attachmentCardsPanel.removeAll();
        if (pendingAttachments.isEmpty()) {
            if (attachmentHintLabel != null) {
                attachmentHintLabel.setText("No files attached");
            }
            JLabel empty = new JLabel("No files attached.");
            empty.setFont(empty.getFont().deriveFont(Font.PLAIN, 11f));
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            if (empty.getForeground() == null) {
                empty.setForeground(Color.GRAY);
            }
            empty.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            attachmentCardsPanel.add(empty);
        } else {
            if (attachmentHintLabel != null) {
                attachmentHintLabel.setText(pendingAttachments.size() + " file(s) attached");
            }
            for (int i = 0; i < pendingAttachments.size(); i++) {
                AttachmentData attachment = pendingAttachments.get(i);
                attachmentCardsPanel.add(buildAttachmentChip(attachment, i));
                if (i < pendingAttachments.size() - 1) {
                    attachmentCardsPanel.add(Box.createVerticalStrut(6));
                }
            }
        }
        if (clearAttachmentsBtn != null) {
            clearAttachmentsBtn.setVisible(!pendingAttachments.isEmpty());
        }
        attachmentCardsPanel.revalidate();
        attachmentCardsPanel.repaint();
        if (attachmentsPanel != null) {
            attachmentsPanel.revalidate();
            attachmentsPanel.repaint();
        }
    }

    private JComponent buildAttachmentChip(AttachmentData attachment, int index) {
        JPanel chip = new JPanel(new BorderLayout(8, 0));
        Color borderColor = UIManager.getColor("Separator.foreground");
        if (borderColor == null) borderColor = UIManager.getColor("Label.foreground");
        if (borderColor == null) borderColor = Color.GRAY;
        Color backgroundColor = UIManager.getColor("Panel.background");
        if (backgroundColor == null) backgroundColor = Color.WHITE;
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        chip.setOpaque(true);
        chip.setBackground(backgroundColor);

        JLabel icon = new JLabel(attachment.image() ? "🖼" : "📎");
        icon.setFont(icon.getFont().deriveFont(Font.PLAIN, 16f));
        chip.add(icon, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(ChatPanelSupport.formatAttachmentTitle(attachment));
        title.setToolTipText(attachment.path() == null ? attachment.displayName() : attachment.path().toString());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        JLabel meta = new JLabel(ChatPanelSupport.formatAttachmentMeta(attachment));
        meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
        meta.setForeground(UIManager.getColor("Label.disabledForeground"));
        if (meta.getForeground() == null) {
            meta.setForeground(Color.GRAY);
        }
        textPanel.add(title);
        textPanel.add(meta);
        chip.add(textPanel, BorderLayout.CENTER);

        JButton removeBtn = new JButton("×");
        removeBtn.setMargin(new Insets(0, 4, 0, 4));
        removeBtn.setFocusable(false);
        removeBtn.setToolTipText("Remove this attachment");
        removeBtn.addActionListener(e -> removeAttachment(index));
        chip.add(removeBtn, BorderLayout.EAST);
        return chip;
    }

    private void removeAttachment(int index) {
        if (index < 0 || index >= pendingAttachments.size()) return;
        pendingAttachments.remove(index);
        refreshAttachmentStrip();
    }

    private void clearAttachments() {
        pendingAttachments.clear();
        refreshAttachmentStrip();
    }

    private void addAttachmentsFromFiles(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        List<AttachmentData> loaded = AttachmentUtil.loadAttachments(paths);
        if (loaded.isEmpty()) {
            appendSystemMessage("No supported attachments were added.");
            return;
        }
        pendingAttachments.addAll(loaded);
        refreshAttachmentStrip();
        refreshTelemetry("Ready", null);
        appendSystemMessage("Attached " + loaded.size() + " file(s) for the next prompt.");
    }

    private TransferHandler buildAttachmentTransferHandler() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support != null && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(data instanceof List<?> rawList)) return false;
                    List<Path> paths = new ArrayList<>();
                    for (Object item : rawList) {
                        if (item instanceof java.io.File file) {
                            if (file.isDirectory()) {
                                try (var stream = java.nio.file.Files.walk(file.toPath())) {
                                    paths.addAll(stream
                                            .filter(java.nio.file.Files::isRegularFile)
                                            .limit(20)
                                            .toList());
                                }
                            } else {
                                paths.add(file.toPath());
                            }
                        }
                    }
                    addAttachmentsFromFiles(paths);
                    return !paths.isEmpty();
                } catch (Exception e) {
                    appendSystemMessage("Could not attach dropped file(s): " + e.getMessage());
                    return false;
                }
            }
        };
    }

    private void recordMistakes(java.util.List<String> mistakeKeys) {
        if (mistakeKeys == null || mistakeKeys.isEmpty()) return;
        String basePath = project.getBasePath();
        if (basePath == null) return;
        for (String key : mistakeKeys) {
            plugin.util.LLMCorrectionsUtil.recordMistake(basePath, key);
        }
    }

    private void injectBlockedWriteFeedback(java.util.List<String> warnings) {
        java.util.List<String> blocked = warnings.stream()
                .filter(w -> w.startsWith("⛔"))
                .collect(java.util.stream.Collectors.toList());
        if (blocked.isEmpty()) return;
        String feedback = "SYSTEM FEEDBACK — The following file operations were rejected:\n" +
                String.join("\n", blocked) + "\n\n" +
                "REQUIRED ACTIONS:\n" +
                "1. If the rejected file exists on disk with errors, delete it immediately using the matching XML tag and path.\n" +
                "2. Do NOT attempt to write or fix that file again.\n" +
                "3. Prefer extracted helpers or pure functions for UI-heavy code instead of testing the wrapper directly.\n" +
                "4. Write tests in the project's detected language and follow its standard test layout.";
        history.add(new ChatMessage("user", feedback));
        history.add(new ChatMessage("assistant",
                "Understood. I will delete the rejected file and write tests for the appropriate helper or language-specific target."));
    }

    /**
     * Hybrid RAG: PSI class-finder → dependency graph → embedding TF-IDF search → rerank → top-8 files.
     * Falls back to empty string if the index is not ready or the project is too small to benefit.
     */
    private String buildRagContext(String query) {
        try {
            if (contextCollector == null) {
                contextCollector = new ContextCollector(project);
            }
            PlannerAgent planner = new PlannerAgent();
            String target   = (forcedTargetSymbol != null && !forcedTargetSymbol.isBlank())
                    ? forcedTargetSymbol
                    : planner.detectTargetSymbol(query);
            forcedTargetSymbol = null;   // one-shot: don't leak into later messages
            String expanded = planner.expandQuery(query);   // BM25 query expansion
            List<RetrievalResult> results = contextCollector.collect(expanded, target, 6);
            if (results.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (RetrievalResult r : results) {
                String lang = r.relativeFilePath() != null && r.relativeFilePath().endsWith(".xml") ? "xml" : "java";
                sb.append("=== ").append(r.relativeFilePath())
                  .append(" [").append(r.symbolType()).append("] ===\n```").append(lang).append("\n");
                sb.append(r.content());
                sb.append("\n```\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, len)));
        }
        return chunks;
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    public JPanel getSwingComponent() {
        return root;
    }

    /**
     * Entry point for the "Generate Tests for This File" action. Starts a fresh
     * conversation, pins the exact class as the RAG target (no guessing), switches
     * to EDITING mode so file ops actually execute, and sends the request.
     */
    public void generateTestsFor(String className) {
        if (className == null || className.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            clearConversation();
            forcedTargetSymbol = className;
            if (modeCombo != null) modeCombo.setSelectedItem("EDITING");
            promptArea.setText("Generate tests for " + className
                    + " using the project's detected language and test framework. Place the file in the matching test location.");
            sendMessage();
        });
    }
}
