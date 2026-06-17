# Local LLM Assistant — IntelliJ IDEA Plugin

A chat plugin for IntelliJ IDEA that connects to a locally running LLM via
[LM Studio](https://lmstudio.ai)'s OpenAI-compatible API.

---

## Requirements

| Requirement | Version |
|---|---|
| IntelliJ IDEA | 2025.2 or later |
| Java (JDK) | 21 |
| Maven | 3.8+ |
| LM Studio | Any recent release |

---

## Build

```bash
# Clone the repository
git clone <repo-url>
cd my-local-llm-plugin

# Compile and package
mvn package

# Output
target/local-llm-assistant.jar
```

> **Note:** The `intellij.lib` property in `pom.xml` defaults to
> `C:/Program Files/JetBrains/IntelliJ IDEA 2025.2.5/lib`.
> If your IntelliJ is installed elsewhere, update that property before building:
>
> ```xml
> <intellij.lib>C:/Your/Path/To/IntelliJ IDEA 2025.x.x/lib</intellij.lib>
> ```

---

## Install the Plugin

### Option A — Install from Disk (recommended for development)

1. Open IntelliJ IDEA.
2. Go to **File → Settings → Plugins** (or press `Ctrl+Alt+S` then navigate to Plugins).
3. Click the **gear icon (⚙)** at the top of the Plugins panel.
4. Select **Install Plugin from Disk…**

   ![Install from disk menu](docs/install-from-disk.png)

5. Browse to `target/local-llm-assistant.jar` and click **OK**.
6. Click **Restart IDE** when prompted.

### Option B — Copy to plugins directory manually

1. Find your IntelliJ plugins directory:
   - Windows: `%APPDATA%\JetBrains\IntelliJIdea2025.2\plugins\`
2. Create a subfolder: `local-llm-assistant\lib\`
3. Copy `target/local-llm-assistant.jar` into that `lib\` folder.
4. Restart IntelliJ IDEA.

---

## Uninstall

1. Go to **File → Settings → Plugins**.
2. Find **Local LLM Assistant** in the **Installed** tab.
3. Click the three-dot menu (⋯) next to the plugin name.
4. Select **Uninstall**.
5. Restart the IDE.

---

## Setup — LM Studio

1. Download and install [LM Studio](https://lmstudio.ai).
2. Download a model (e.g. `qwen2.5-coder-7b-instruct`).
3. Go to the **Local Server** tab in LM Studio.
4. Click **Start Server** — it starts on `http://127.0.0.1:1234` by default.
5. Load a model in the server (select it from the dropdown at the top of the Server tab).

---

## Using the Plugin

### Open the Chat Window

After installation, the **Local LLM** tool window appears in the right-side stripe.
Click it to open the panel.

```
View → Tool Windows → Local LLM
```

### Configure Settings

1. Click the **Settings** section header to expand it.
2. **Server Endpoint** — leave as `http://127.0.0.1:1234` unless you changed LM Studio's port.
3. Click **Refresh Models** — the model dropdown populates automatically from the running LM Studio server.
4. Select the model you want to use.
5. Click **Save Settings** — your endpoint and model are persisted across IDE restarts.

### Chat

1. Type your message in the input field at the bottom of the panel.
2. Press **Enter** or click **Send**.
3. The spinner shows while the model is generating a response.
4. The response appears in the conversation area above.
5. Click **Clear** to reset the conversation history.

---

## Project Structure

```
my-local-llm-plugin/
├── pom.xml
├── README.md
└── src/main/
    ├── java/plugin/
    │   ├── toolwindow/
    │   │   └── ChatToolWindowFactory.java   # Registers the tool window with IntelliJ
    │   ├── ui/
    │   │   └── ChatPanel.java               # JavaFX UI (embedded via JFXPanel)
    │   ├── settings/
    │   │   └── PluginSettings.java          # Persistent settings (endpoint, model)
    │   └── llm/
    │       ├── LMStudioClient.java          # HTTP client for LM Studio API
    │       └── model/
    │           └── ChatMessage.java         # Chat message record (role + content)
    └── resources/
        └── META-INF/
            └── plugin.xml                   # Plugin descriptor
```

---

## API Endpoints Used

| Purpose | Method | URL |
|---|---|---|
| List available models | `GET` | `/v1/models` |
| Send a chat message | `POST` | `/v1/chat/completions` |

LM Studio implements the OpenAI-compatible API, so any OpenAI-compatible
local server (e.g. Ollama with `ollama serve`) works as a drop-in replacement
by just changing the endpoint URL.

---

## Troubleshooting

**"Error: Connection refused"**
- Make sure LM Studio's local server is running.
- Verify the endpoint in Settings matches LM Studio's port.

**Model dropdown is empty after Refresh**
- A model must be loaded in LM Studio's Server tab before it appears in the list.
- Check LM Studio is running and the server is started.

**Plugin not visible after install**
- Make sure you restarted IntelliJ after installation.
- Check **File → Settings → Plugins → Installed** to confirm it is enabled.

**Build fails with "system path does not exist"**
- Update `<intellij.lib>` in `pom.xml` to match your actual IntelliJ installation path.
