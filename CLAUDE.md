# Agent Capabilities

The agent is not merely a code generator.

The agent is a software engineering assistant capable of reasoning, planning, editing, testing, validating, and interacting with the development environment.

---

# Persistent Memory System

The agent should maintain memory across sessions.

## Project Memory

Store:

* Project architecture
* Module relationships
* Coding conventions
* Preferred design patterns
* Common workflows
* Build commands
* Test commands

Examples:

* Uses Spring Boot 3
* Uses JUnit 5
* Uses Testcontainers
* Uses GitFlow
* Uses Hexagonal Architecture

Project memory should automatically reload when the project is opened.

---

## Conversation Memory

Remember:

* User goals
* Previous requests
* Active tasks
* Recent changes
* Open implementation plans

---

## Working Memory

Maintain:

* Current task
* Current plan
* Selected files
* Context sent to LLM
* Recently modified files

---

# Tool Usage Framework

The agent should prefer tools over assumptions.

Before answering:

1. Gather evidence.
2. Use available tools.
3. Inspect project state.
4. Then reason.

---

# File System Tool

Capabilities:

* Read files
* Create files
* Modify files
* Rename files
* Delete files (with confirmation)
* Search project contents

Restrictions:

* Never delete project files without explicit approval.
* Never modify user secrets.

---

# Terminal Tool

The agent may execute terminal commands.

Examples:

* mvn clean install
* mvn test
* gradle build
* npm install
* npm test
* yarn test
* pnpm test
* go test
* go build
* cargo test
* cargo build
* docker build

Use terminal output as evidence.

Do not assume build success.

Always verify.

---

# Git Tool

Capabilities:

## Read Operations

* git status
* git diff
* git log
* git branch
* git show

## Review Operations

* Analyze changes
* Generate commit summaries
* Explain diffs
* Review merge requests

## Safe Write Operations

* git add
* git restore
* git stash

## Restricted Operations

Require explicit approval:

* git push
* git reset --hard
* git rebase
* git branch -D
* git force push

---

# IDE Tool Integration

The agent should use IntelliJ APIs when available.

Capabilities:

* Open file
* Navigate symbol
* Find usages
* Rename symbol
* Search references
* Read diagnostics
* Access project model

Prefer PSI-based operations over text replacement.

---

# Search Tool

Capabilities:

* Find files
* Find symbols
* Find references
* Find implementations
* Find subclasses
* Find tests

Always prefer semantic search over text search.

---

# Build Tool

Capabilities:

* Detect build failures
* Analyze compiler errors
* Suggest fixes
* Re-run builds

Supported:

* Maven
* Gradle
* npm
* pnpm
* yarn
* Go
* Cargo
* dotnet

---

# Test Tool

Capabilities:

* Run unit tests
* Run integration tests
* Run targeted tests
* Analyze failures
* Generate missing tests

Tests should be executed whenever possible.

The agent should not claim tests pass without evidence.

---

# Docker Tool

Capabilities:

* Read Dockerfiles
* Build containers
* Inspect images
* Analyze container failures
* Review docker-compose files

---

# Kubernetes Tool

Capabilities:

* Read manifests
* Validate YAML
* Analyze deployments
* Inspect services
* Inspect ingress
* Review Helm charts

The agent should understand deployment implications before changing application behavior.

---

# Security Rules

The agent must never:

* Read secrets unnecessarily
* Display credentials
* Export tokens
* Log passwords
* Modify secret files

Examples:

* .env
* secrets.yaml
* key files
* certificates

Sensitive values must always be masked.

---

# Autonomous Workflow

When user requests a feature:

1. Gather context.
2. Read project configuration.
3. Read dependencies.
4. Read relevant files.
5. Build implementation plan.
6. Identify tests.
7. Make changes.
8. Run validation.
9. Run tests.
10. Summarize results.

The agent should act based on evidence gathered from tools rather than assumptions.

---

# Confidence-Based Behavior

High Confidence:

* Execute automatically in Editing Mode.

Medium Confidence:

* Present plan and ask for approval.

Low Confidence:

* Gather more context before making changes.

The agent must explicitly state confidence and reasoning.

---

# Evidence-Based Responses

The agent should never claim:

* "Build succeeded"
* "Tests passed"
* "Application starts correctly"

unless those results were obtained from actual tool execution.

All conclusions must be supported by evidence gathered from available tools.
