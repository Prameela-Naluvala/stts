pipeline {
  agent none
  options {
    disableConcurrentBuilds()
    timeout(time: 3, unit: 'HOURS')
    timestamps()
    ansiColor('xterm')
  }

  stages {
    stage('Build Docker Container') {
      when {
        anyOf {
          branch 'release'
          branch 'main'
          branch 'stats-legacy'
        }
        beforeAgent true
      }
      agent {
        label 'docker'
      }
      steps {
        script { 
          sh """
          docker build -f Dockerfile.prod -t statsmoduleprod .
          docker images
          touch confaws
          echo '[default]' > confaws
          echo 'region = eu-west-1' >> confaws
          docker run -d -t --name statscontainer statsmoduleprod:latest
          docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && pip3 install -r requirements.txt'
          docker exec statscontainer sh -c 'touch /root/.aws/config'
          docker cp confaws statscontainer:/root/.aws/config                                   
          """
        }
      }
      post {
        failure {
          script {
            if("${env.BRANCH_NAME}" == 'master' || "${env.BRANCH_NAME}" == 'highwirefundware') {
              office365ConnectorSend message: "Build Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: buildfailureWebhook
            }
          }
        }
      }
    }

    stage('Deploy to Stage') {
      when {
        branch 'release'
        beforeAgent true
      }
      agent {
        label 'docker'
      }
      stages {
        stage('Build Migrations docker image') {
          steps {
            dir("migrations") {
              sh "docker build -t local/migrations ."
            }
          }
        }

        stage('Liquibase migration') {
          steps {
            sh """
              docker run --rm --entrypoint='' \
                -w /work/migrations \
                -v \$(pwd)/migrations:/work/migrations \
                -e AWS_REGION='${region}' \
                -e AWS_ENV_PATH='/mgmt/jenkins/stage/${appName}/' \
                  local/migrations \
                    ./liquibaseMigration.sh
            """
          }
          post {
            always {
              dir('migrations') {
                archiveArtifacts artifacts: 'migration-plan.sql', fingerprint: false
              }
            }
            failure {
              script {
                if("${env.BRANCH_NAME}" == 'master') {
                  office365ConnectorSend message: "Liquibase Migration for Stage Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: buildfailureWebhook
                }
              }
            }
          }
        }

        stage('Deploy') {
          steps {
            sh "docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && chalice deploy --stage stage --connection-timeout 300'"
          }
          post {
            success {
              office365ConnectorSend message: "Deployed Stage ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Success", color: "50df16", webhookUrl: devopsWebhook
            }
            aborted {
              office365ConnectorSend message: "Could not deploy Stage ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Aborted", color: "999999", webhookUrl: buildfailureWebhook
            }
          }
        }
      }
    }

    // ... other stages ...

  }

  post {
    failure {
      script {
        if("${env.BRANCH_NAME}" == 'main' || "${env.BRANCH_NAME}" == 'release' || "${env.BRANCH_NAME}" == 'stats-legacy' ) {
          office365ConnectorSend message: "CI Pipeline Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: build