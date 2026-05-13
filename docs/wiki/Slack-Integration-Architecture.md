# Slack Integration Architecture

This project utilizes the **Slack Bolt SDK** with **Socket Mode** to handle interactions, avoiding the need for a publicly accessible HTTP webhook server.

## Overview
The Slack integration is handled primarily through `SlackBotGateway.kt`. By using Socket Mode, the application initiates a WebSocket connection to Slack, allowing it to receive events (messages, mentions, reactions) and send responses via API calls over the established connection.

## Key Components

### 1. `SlackBotGateway.kt`
This is the central hub for Slack interactions:
- **Connection:** Uses `com.slack.api.bolt.socket_mode.SocketModeApp` to maintain a persistent connection using the `SLACK_APP_TOKEN`.
- **Event Handling:** Registers listeners for various Slack events:
  - `AppMentionEvent`: Triggered when the bot is mentioned.
  - `MessageEvent`: Handles standard messages.
  - `ReactionAddedEvent`: Monitors reactions for feedback or trigger actions.
  - `AssistantThreadStartedEvent`/`ContextChangedEvent`: Manages assistant thread life cycles.
- **API Interaction:** Uses `MethodsClient` to perform actions like `chatPostMessage` or `viewsPublish`.

### 2. Initialization (`Main.kt`)
The gateway is initialized at application startup:
- Validates the presence of `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN`.
- If credentials are valid, it instantiates `SlackBotGateway` and triggers `start()`, which launches the Socket Mode loop.

## Benefits
- **Security:** No need to expose a public HTTP endpoint; the application pushes requests out to Slack.
- **Dev-Friendly:** Works seamlessly in local development environments behind firewalls/NATs without extra tunnel configurations.
- **Event-Driven:** Leverages the Bolt SDK's robust, asynchronous event-handling patterns.
