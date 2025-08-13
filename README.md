# Jenkins Stage Metrics Plugin

A Jenkins plugin that captures and sends pipeline stage metrics to external analytics systems.

## Features

- **Stage Metrics**: Captures duration, start time, and status for each pipeline stage
- **Build Tool Detection**: Extracts build tools from pipeline and stage-level environment variables
- **Status Tracking**: Reports SUCCESS, FAILURE, or ABORTED status for each stage
- **HTTP Integration**: Sends metrics to configurable HTTP endpoints
- **SSL Support**: Configurable SSL certificate validation

## Installation

1. Download the latest `.hpi` file from the [releases page](https://github.com/your-username/jenkins-stage-metrics-plugin/releases)
2. In Jenkins, go to **Manage Jenkins** → **Manage Plugins** → **Advanced**
3. Upload the `.hpi` file under "Upload Plugin"
4. Restart Jenkins

## Configuration

1. Go to **Manage Jenkins** → **Configure System**
2. Find the "Stage Metrics Configuration" section
3. Configure your HTTP endpoint URL
4. Optionally disable SSL certificate validation for testing environments

## Usage

The plugin automatically captures metrics from all Pipeline jobs. No additional configuration required in your Jenkinsfile.

### Example Pipeline
```groovy
pipeline {
    agent any
    environment {
        BUILD_TOOL = "maven"
    }
    stages {
        stage('Build') {
            environment {
                BUILD_TOOL = "gradle"
            }
            steps {
                sh 'echo "Building..."'
            }
        }
    }
}
```

### Sample Metrics Payload
```json
{
  "runId": "123",
  "jobName": "my-pipeline",
  "jobUrl": "http://jenkins/job/my-pipeline/",
  "buildTool": "maven",
  "name": "Build",
  "startTimeMillis": 1234567890,
  "durationMillis": 5000,
  "status": "SUCCESS",
  "stageBuildTool": "gradle"
}
```

## Requirements

- Jenkins 2.426.3 or later
- Pipeline plugin (workflow-aggregator)

## License

MIT License

