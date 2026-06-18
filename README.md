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
> `C:/Program Files/JetBrains/IntelliJIdea2025.2.5/lib`.
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

# Option B — Copy to plugins directory manually

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

Thank you for using Local LLM Assistant!