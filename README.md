# Intro
This is a opentelemetry custom agent

# Purpose
Purpose is for development teams to define declarative configuration for dynamically adding proprties to spans

# Information
- draft folder - the first idea on paper
- openspec folder - from idea to spec
- app folder - example app to test the agent in real live
- src folder - the agent code

# How to run it?
- in app/: mvn clean install. This results in am example application 
- start the opentelemetry collector stub in app/ on port 4317
- in ./: gradle build
- run the run-agent.sh script to start the example application with the custom agent
- post data again the cars endpoint of the example application using app/car.http

# Configuration
```xml
<configuration>
    <static>
        <attribute key="domain" value="cars"/>
        <attribute key="team" value="winning"/>
    </static>
    <dynamic>
        <attribute key="brand" path="com.example.Car.brand"/>
        <attribute key="passengers" path="com.example.Car.passengers[1].name"/>
    </dynamic>
</configuration>
```

# Used tooling
- opencode with gitnexus, ponytail and openspec
- gpt-5.6 luna
- glm 5.2

# Configuration Webserver

The agent embeds a lightweight HTTP server on `http://127.0.0.1:14317/` (loopback only, no external access) that lets operators view and update the declarative configuration at runtime without restarting the JVM.

## Usage

1. Open `http://127.0.0.1:14317/` in a browser.
2. The **Current Configuration** section shows the active XML.
3. Paste new XML into the textarea and click **Activate** to reload. The response shows rule and classloader counts. Invalid XML returns an error without changing the active state.
4. Click **Load Original** to fill the textarea with the decoded `OTEL_CUSTOM_AGENT_CONFIG` startup value, then **Activate** to revert to the default.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | HTML configuration UI |
| GET | `/config/current` | Active XML (`text/xml`) or 404 |
| GET | `/config/original` | Decoded startup XML (`text/xml`), 404 if env var absent, 500 on invalid Base64 |
| POST | `/config` | Reload with raw XML body (`text/xml`); 200 on success, 400 on invalid XML |

## Limitations

- The server binds to `127.0.0.1` only; no authentication or TLS.
- Reload updates the rule index for classloaders registered at startup; it cannot instrument new root classes not matched at startup.
- If port `14317` is already in use, the server logs a warning and remains down; enrichment continues with the startup configuration.

# Tokens

## First attempt version 1
```
GPT-5.6 Luna
386,423 tokens
$23.67 spent
```
## Webserver added 
```
GLM 5.2
201,564 tokens
$14.39 spent
```
