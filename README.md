# Local LLM Assistant — IntelliJ Plugin

A JetBrains plugin that brings local LLM chat (Ollama / LM Studio) directly into your IDE.
Supports streaming responses, file context injection, image paste, file attachments, and
automatic code editing with diff preview.

Works in **IntelliJ IDEA**, **GoLand**, and any other JetBrains IDE that runs on the
IntelliJ platform.

---

## Requirements

| Requirement | Details |
|-------------|---------|
| JetBrains IDE | IntelliJ IDEA 2024.1+ (Community or Ultimate), GoLand 2024.1+, etc. |
| Java | JDK 17+ on PATH (needed to build from source) |
| Maven | 3.8+ on PATH (needed to build from source) |
| LLM backend | [Ollama](https://ollama.com) and/or [LM Studio](https://lmstudio.ai) running locally |

---

## Building from Source

```bash
git clone <repo-url>
cd my-local-llm-plugin
mvn clean package
```

The build produces two jars in `target/`:

| Jar | Purpose |
|-----|---------|
| `my-local-llm-plugin-1.0.0.jar` | Plugin sources only (no dependencies) |
| `my-local-llm-plugin-1.0.0-jar-with-dependencies.jar` | **Use this one** — includes Gson and Apache HttpClient5 |

> **Note:** The build references IntelliJ SDK jars via system scope from
> `C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.5\lib\`.
> If your IntelliJ is installed elsewhere, update the `<intellij.home>` property
> in `pom.xml` before building.

---

## Installation

### Option A — Install from the built JAR (recommended)

1. Open your JetBrains IDE.
2. Go to **File → Settings** (or **IntelliJ IDEA → Settings** on macOS).
3. Navigate to **Plugins**.
4. Click the gear icon (**⚙**) at the top → **Install Plugin from Disk…**
5. Select `target/my-local-llm-plugin-1.0.0-jar-with-dependencies.jar`.
6. Click **OK** and restart the IDE when prompted.

### Option B — Copy to plugins directory manually

1. Locate your IDE plugins directory:
   - **Windows:** `%APPDATA%\JetBrains\<IDE><version>\plugins\`
   - **macOS:** `~/Library/Application Support/JetBrains/<IDE><version>/plugins/`
   - **Linux:** `~/.local/share/JetBrains/<IDE><version>/plugins/`
2. Create a folder: `plugins/local-llm-assistant/lib/`
3. Copy `my-local-llm-plugin-1.0.0-jar-with-dependencies.jar` into that `lib/` folder.
4. Restart the IDE.

---

## First-time Setup

### 1. Start your LLM backend

**Ollama** (default, port 11434):
```bash
ollama serve
ollama pull llama3.2        # or any model you prefer
```

**LM Studio** (default, port 1234):
1. Open LM Studio → **Local Server** tab.
2. Load a model and click **Start Server**.

### 2. Configure the plugin

1. Go to **File → Settings → Tools → Local LLM Assistant**.
2. Select your backend (**Ollama** or **LM Studio**).
3. Verify the URL (defaults are pre-filled):
   - Ollama: `http://localhost:11434`
   - LM Studio: `http://localhost:1234`
4. Click **Test Connection**.
   - A green **✓ Connected** message confirms the backend is reachable.
   - The **Model** dropdown is populated automatically from the running backend.
5. Select your model from the dropdown.
6. Optionally adjust:
   - **System Prompt** — instructions prepended to every conversation.
   - **Temperature** — creativity slider (0.0 = deterministic, 1.0 = creative).
   - **Max Tokens** — maximum response length.
   - **Auto-apply edits** — skip the diff preview and write files directly (use carefully).
7. Click **Apply** / **OK**.

---

## Opening the Chat Panel

The chat panel appears as a tool window on the **right side** of the IDE.

- Click **Local LLM Assistant** in the right sidebar, or
- Go to **View → Tool Windows → Local LLM Assistant**.

---

## Using the Plugin

### Basic chat

1. Type your message in the text area at the bottom.
2. Press **Enter** to send (or click **Send**).
   - **Shift+Enter** inserts a newline without sending.
3. The assistant response streams in token by token in the chat area.
4. Click **Stop** at any time to cancel a running response.
5. Click **Clear** (toolbar) to reset the conversation history.

### Inject context with @ commands

Type these keywords anywhere in your message:

| Command | What it injects |
|---------|-----------------|
| `@file` | Full content of the currently open file |
| `@selection` | Currently selected text in the editor |
| `@project` | Two-level file tree of your project |

Example: `@file Can you refactor this class to use the builder pattern?`

### Attach files via the clip button

1. Click **📎** next to the input area.
2. Pick a file:
   - **Images** (jpg, png, gif, webp) — converted to base64 and sent to vision-capable models.
   - **Text files** (java, kt, go, py, md, json, yaml, xml, etc.) — injected as a fenced code block in the prompt.
3. A chip appears above the input showing the file name.
4. Click **×** on a chip to remove it before sending.

### Paste an image from clipboard

1. Copy any image (screenshot, diagram, etc.) to your clipboard.
2. Click inside the message input area.
3. Press **Ctrl+V**.
   - If the clipboard contains an image, a thumbnail chip appears instead of pasting text.
   - If it contains text, normal paste behaviour applies.

### Send selected code via right-click or shortcut

1. Select any text or code in the editor.
2. Right-click → **Send Selection to Local LLM**, or press **Ctrl+Shift+L**.
3. The chat panel opens and the selection is sent immediately.

---

## Auto File Editing

When the model suggests code changes in the format below, the plugin shows **Copy** and
**Apply to File** buttons directly in the response bubble.

Model output format (the system prompt enforces this automatically):

````
```java:src/main/java/com/example/MyClass.java
// complete file content here
```
````

### Applying a change

1. The assistant bubble shows a code block with a header bar displaying the language and file path.
2. Click **📝 Apply to File**:
   - **File exists** → a diff dialog opens showing the before/after changes with colour highlighting. Click **Apply Changes** to write, or **Cancel** to discard.
   - **File does not exist** → a confirmation dialog asks whether to create the file.
3. After writing, the IDE refreshes the VFS automatically.

### Applying multiple files

Each file block in a single response gets its own **Apply to File** button. Apply them
independently in any order.

### Auto-apply mode

Enable **Auto-apply edits** in Settings to skip the diff dialog and write immediately.
A confirmation dialog still appears for new files.

---

## Settings Reference

| Setting | Default | Description |
|---------|---------|-------------|
| Backend | Ollama | Which LLM server to use |
| Ollama URL | `http://localhost:11434` | Ollama REST API base URL |
| LM Studio URL | `http://localhost:1234` | LM Studio OpenAI-compatible API base URL |
| Model | _(from backend)_ | Model name to use for chat |
| System Prompt | `You are a helpful coding assistant.` | Prepended to every request |
| Temperature | 0.70 | Sampling temperature |
| Max Tokens | 4096 | Maximum tokens in each response |
| Auto-apply edits | Off | Write files without showing diff |

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| **Enter** | Send message |
| **Shift+Enter** | New line in input |
| **Ctrl+V** (in input area) | Paste image from clipboard |
| **Ctrl+Shift+L** (in editor) | Send selected text to chat |

---

## Troubleshooting

**"Backend not available" warning on startup**
- Make sure Ollama (`ollama serve`) or LM Studio server is running before opening the IDE.
- Check the URL in Settings matches the port your backend is actually using.
- Firewalls or VPNs may block localhost connections — try disabling them temporarily.

**Model dropdown is empty after Test Connection**
- Ollama: run `ollama list` in a terminal to confirm at least one model is downloaded.
- LM Studio: load a model in the app before starting the server.

**Build fails with "cannot find symbol"**
- Update `<intellij.home>` in `pom.xml` to point to your actual IntelliJ installation directory.
- The SDK jars are resolved from that path at compile time.

**Responses truncated**
- Increase **Max Tokens** in Settings (up to 32000).
- Some models have a built-in context window limit; switch to a model with a larger context.

**Images not sent to model**
- Vision support depends on the model. Use a multimodal model (e.g. `llava`, `llama3.2-vision` in Ollama, or a vision model in LM Studio).

---

## Project Structure

```
my-local-llm-plugin/
├── pom.xml
└── src/main/
    ├── java/plugin/
    │   ├── actions/
    │   │   └── SendSelectionAction.java      # Ctrl+Shift+L action
    │   ├── context/
    │   │   ├── FileContextReader.java         # @file / @selection helpers
    │   │   └── ProjectContextBuilder.java     # @project file tree builder
    │   ├── llm/
    │   │   ├── LLMClient.java                 # Interface
    │   │   ├── OllamaClient.java              # Ollama NDJSON streaming
    │   │   ├── LMStudioClient.java            # LM Studio SSE streaming
    │   │   └── model/
    │   │       ├── ChatMessage.java
    │   │       ├── ImageAttachment.java
    │   │       ├── TextAttachment.java
    │   │       └── StreamChunk.java
    │   ├── settings/
    │   │   ├── PluginSettings.java            # Persistent settings state
    │   │   └── SettingsUI.java                # Settings panel under Tools
    │   ├── ui/
    │   │   ├── ChatPanel.java                 # Main chat UI
    │   │   ├── MessageBubble.java             # Chat bubbles + Apply buttons
    │   │   ├── FileChip.java                  # Attachment chips
    │   │   ├── FileEditor.java                # File write + diff utility
    │   │   └── DiffViewer.java                # Unified diff renderer
    │   └── MyPluginFactory.java               # Tool window factory
    └── resources/META-INF/plugin.xml
```

---

## License

MIT
