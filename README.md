# Stage Metrics Plugin

A Jenkins plugin for collecting and sending pipeline stage metrics to an external analytics system.

## Overview

This plugin automatically captures metrics from Jenkins Pipeline stages and sends them to a configured endpoint. It extracts stage timing information, build tools used (from `sh` step labels), and other relevant metadata.

## Features

- **Automatic Stage Detection**: Identifies pipeline stages and their execution times
- **Build Tool Extraction**: Captures build tool information from `sh(label: 'tool-name', script: '...')` steps
- **Configurable Endpoint**: Send metrics to any REST API endpoint
- **Basic Authentication**: Supports username/password authentication
- **SSL Support**: Option to trust self-signed certificates
- **Error Tracking**: Configuration page shows last error for troubleshooting

## Configuration

1. Go to **Manage Jenkins** → **Configure System**
2. Find the **Stage Metrics Plugin Configuration** section
3. Configure the following settings:
   - **Username**: Username for Basic Auth
   - **Password**: Password for Basic Auth  
   - **Stage Metrics Endpoint URL**: The URL where metrics will be sent
   - **Trust Self-Signed Certificates**: Check if your endpoint uses self-signed SSL certificates

## Usage

The plugin works automatically once configured. For each pipeline build:

1. When a pipeline completes, the plugin scans all stages
2. For each stage, it extracts:
   - Stage name
   - Start time and duration
   - Build tool (from first `sh` step label in the stage)
3. Sends individual HTTP POST requests for each stage to your configured endpoint

### Example Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                sh(label: 'maven', script: 'mvn clean compile')
            }
        }
        stage('Test') {
            steps {
                sh(label: 'gradle', script: 'gradle test')
            }
        }
    }
}
```

In this example:
- The "Build" stage will have `stageBuildTool: "maven"`
- The "Test" stage will have `stageBuildTool: "gradle"`

## Data Format

The plugin sends JSON data in this format:

```json
{
    "runId": "123",
    "jobName": "my-pipeline",
    "jobUrl": "http://jenkins.example.com/job/my-pipeline/",
    "buildTool": "unknown",
    "name": "Build",
    "startTimeMillis": 1642680000000,
    "durationMillis": 5000,
    "stageBuildTool": "maven"
}
```

## API Endpoint

The data is sent via POST to: `{endpointUrl}/rest/v1.0/objects?request=sendReportingData&payload={encoded-json}&reportObjectTypeName=ci_metrics`

## Troubleshooting

- Check the **Last Error** field in the configuration page for error messages
- Verify your endpoint URL is correct and accessible from Jenkins
- Ensure authentication credentials are valid
- For SSL issues, try enabling "Trust Self-Signed Certificates"

## Development

### Building

```bash
mvn clean package
```

### Fast Development Workflow (for existing Jenkins server)

#### Option 1: Hot Reload (Recommended)
1. Enable development mode in Jenkins Script Console:
   ```groovy
   System.setProperty("hudson.hpi.run", "true")
   System.setProperty("stapler.jelly.noCache", "true")
   ```
2. Build plugin: `mvn clean package`
3. Upload via **Manage Jenkins** → **Manage Plugins** → **Advanced**
4. **Uncheck** "Restart Jenkins when installation is complete"

#### Option 2: Direct File Replacement
```bash
# Build the plugin
mvn clean package

# Replace plugin file directly (adjust path to your Jenkins)
copy target\demo.hpi "C:\Path\To\Jenkins\plugins\demo.hpi"

# Quick service restart (faster than full restart)
net stop jenkins && net start jenkins
```

#### Option 3: Jenkins CLI
```bash
# One-time setup
curl -O http://your-jenkins:8080/jnlpJars/jenkins-cli.jar

# Deploy plugin
java -jar jenkins-cli.jar -s http://your-jenkins:8080/ install-plugin target/demo.hpi -restart
```

### Running in Development (Local Instance)

```bash
mvn hpi:run
```

### Testing

```bash
mvn test
```

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

