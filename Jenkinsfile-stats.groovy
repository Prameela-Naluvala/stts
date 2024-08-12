devopsWebhook = 'https://fundsaxis2.webhook.office.com/webhookb2/60d237ee-f3a2-4f04-adb7-27959c127730@c56018d0-aefb-49ba-adda-e4ddb4438bcf/IncomingWebhook/b44f555c2135422c81d3b9a2d7244d72/639cc0b8-ad5d-4c14-8940-211e40ef15c7'
buildfailureWebhook = 'https://fundsaxis2.webhook.office.com/webhookb2/60d237ee-f3a2-4f04-adb7-27959c127730@c56018d0-aefb-49ba-adda-e4ddb4438bcf/IncomingWebhook/39637e2c0b3348349cb3fb4be37cc4fa/a8db0d6a-19a5-4f60-8453-d3d41add906f'
approvalrequestWebhook = 'https://fundsaxis2.webhook.office.com/webhookb2/60d237ee-f3a2-4f04-adb7-27959c127730@c56018d0-aefb-49ba-adda-e4ddb4438bcf/IncomingWebhook/4638f756543e4b6a9da685c955a4b555/a8db0d6a-19a5-4f60-8453-d3d41add906f'

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
      
      stages {
        stage('Docker build') {
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
                  office365ConnectorSend message: "Build Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: backendWebhook
                }
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
          steps 
            
            sh "docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && chalice deploy --stage stage --connection-timeout 300'"
          }
          post {
            success {
              office365ConnectorSend message: "Deployed Stage ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Success", color: "50df16", webhookUrl: backendWebhook
            }
            aborted {
              office365ConnectorSend message: "Could not deploy Stage ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Aborted", color: "999999", webhookUrl: backendWebhook
            }
          }
        }
      }
    }
    stage('Stage Tests') {
            when {
                branch 'release'
                beforeAgent true
            }
            agent {
                label 'docker'
            }
            steps {
                sh "docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && export LAMBDA_STAGE_NAME=stage && py.test tests/'"              
           }
    }
     stage('Deploy to Prod-cons-1') {
      when {
        branch 'main'
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
                -e AWS_ENV_PATH='/mgmt/jenkins/prod-cons-1/${appName}/' \
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
                if("${env.BRANCH_NAME}" == 'main') {
                  office365ConnectorSend message: "Liquibase Migration for prod-cons-1 Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: buildfailureWebhook
                }
              }
            }
          }
        }

        stage('Deploy') {
          steps {
            
            sh "docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && chalice deploy --stage prod-cons-1 --connection-timeout 300'"
          }
          post {
                success {
                office365ConnectorSend message: "Deployed Stats Prod-Cons-1 ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Success", color: "50df16", webhookUrl: devopsWebhook
                }
                aborted {
                office365ConnectorSend message: "Could not deploy Stats Prod-Cons-1 ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Aborted", color: "999999", webhookUrl: buildfailureWebhook
                }
            }
        }
      }
    }
    stage("Get Approval for Prod-v2 Deployment") {
          when {
                branch 'main'
                beforeAgent true
            }
            agent {
                label 'docker'
            }                       
            steps {
                office365ConnectorSend message: "Please Approve to Deploy in Production!! - ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Approval_Pending", color: "50df16", webhookUrl: approvalrequestWebhook
                input "Please Approve to Proceed with Deployment"
            }
            post {
            success {
              office365ConnectorSend message: "Got Approval for Deployment!! - ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Success", color: "50df16", webhookUrl: approvalrequestWebhook
            }
            aborted {
              office365ConnectorSend message: "Approval stage aborted, Could not deploy!- ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Aborted", color: "999999", webhookUrl: approvalrequestWebhook
            }
          }
    }
    stage('Deploy to Prod-V2') {
      when {
        branch 'stats-legacy'
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
                -e AWS_ENV_PATH='/mgmt/jenkins/prod-v2/${appName}/' \
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
                if("${env.BRANCH_NAME}" == 'main') {
                  office365ConnectorSend message: "Liquibase Migration for prod-v2 Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: buildfailureWebhook
                }
              }
            }
          }
        }

        stage('Deploy') {
          steps {
            sh "docker exec statscontainer sh -c 'cd /src/lambda/statsmodule && chalice deploy --stage prod --connection-timeout 300'"
          }
          post {
                success {
                office365ConnectorSend message: "Deployed Stats Prod-V2 ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Success", color: "50df16", webhookUrl: devopsWebhook
                }
                aborted {
                office365ConnectorSend message: "Could not deploy Stats Prod-V2 ${env.JOB_NAME} - ${env.BUILD_NUMBER}", status: "Aborted", color: "999999", webhookUrl: buildfailureWebhook
                }
            }
        }
      }
    } 

    }        
    post {
        failure {
         script {
            if("${env.BRANCH_NAME}" == 'main' || "${env.BRANCH_NAME}" == 'release' || "${env.BRANCH_NAME}" == 'stats-legacy' ) {
            office365ConnectorSend message: "CI Pipeline Failed! ${env.JOB_NAME}", status: "Failure", color: "d00000", webhookUrl: buildfailureWebhook
            }
        }
        }
        unstable {
         script {
            if("${env.BRANCH_NAME}" == 'main' || "${env.BRANCH_NAME}" == 'release' || "${env.BRANCH_NAME}" == 'stats-legacy') {
            office365ConnectorSend message: "CI Pipeline is unstable. ${env.JOB_NAME}", status: "Unstable", color: "ffcc00", webhookUrl: buildfailureWebhook
            }
        }
        }
        aborted {
         script {
            if("${env.BRANCH_NAME}" == 'main' || "${env.BRANCH_NAME}" == 'release' || "${env.BRANCH_NAME}" == 'stats-legacy') {
            office365ConnectorSend message: "CI Pipeline Aborted! ${env.JOB_NAME}", status: "Aborted", color: "999999", webhookUrl: buildfailureWebhook
            }
        }
        }
    }
}