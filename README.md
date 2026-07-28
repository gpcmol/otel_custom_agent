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

# First attempt version 1
```
386,423 tokens
$23.67 spent
```
