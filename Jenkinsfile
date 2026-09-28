pipeline {
    agent any

    environment {
        // Jenkins runs as its own user and cannot see SDKMAN JDKs under
        // /home/lordibe, so the system-wide Temurin install is named explicitly.
        JAVA_HOME          = "/usr/lib/jvm/temurin-25-jdk-amd64"
        PATH               = "/usr/lib/jvm/temurin-25-jdk-amd64/bin:${env.PATH}"
        DOCKER_IMAGE       = 'lordibe/stocksync'
        REPO_URL           = 'https://github.com/Emmaibe/stocksync.git'
        DOCKER_CREDENTIALS = credentials('dockerhub-credentials')
        DOCKER_TAG         = "${BUILD_NUMBER}"
    }

    stages {

        stage('Checkout') {
            steps {
                git branch: 'main',
                        url: "${REPO_URL}",
                        credentialsId: 'github-credentials'
            }
        }

        // The tests actually run here, unlike the -DskipTests build used on the
        // older projects. They need a Docker daemon for Testcontainers, which
        // this agent already has because it builds images two stages down.
        //
        // If Testcontainers cannot reach Docker on this agent, run
        //   mvn clean package -DskipITs
        // to keep the fast unit tests and skip the integration suite — rather
        // than skipping every test and shipping untested code.
        stage('Build & Test') {
            steps {
                sh 'mvn clean verify'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                            testResults: 'target/surefire-reports/*.xml,target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Docker Build') {
            steps {
                sh "docker build -t ${DOCKER_IMAGE}:${DOCKER_TAG} ."
                sh "docker tag ${DOCKER_IMAGE}:${DOCKER_TAG} ${DOCKER_IMAGE}:latest"
            }
        }

        stage('Docker Push') {
            steps {
                sh "echo ${DOCKER_CREDENTIALS_PSW} | docker login -u ${DOCKER_CREDENTIALS_USR} --password-stdin"
                sh "docker push ${DOCKER_IMAGE}:${DOCKER_TAG}"
                sh "docker push ${DOCKER_IMAGE}:latest"
            }
        }

        stage('Deploy via Ansible') {
            steps {
                sh """
                    ansible-playbook ansible/deploy.yml \
                        -i ansible/inventory.ini \
                        --extra-vars "image_tag=${DOCKER_TAG}" \
                        -v
                """
            }
        }
    }

    post {
        success {
            echo "========================================="
            echo "  PIPELINE COMPLETE — APP IS LIVE"
            echo "========================================="
        }
        failure {
            echo "========================================="
            echo "  PIPELINE FAILED — CHECK LOGS ABOVE"
            echo "========================================="
        }
        always {
            sh "docker rmi ${DOCKER_IMAGE}:${DOCKER_TAG} || true"
            sh "docker logout || true"
        }
    }
}
