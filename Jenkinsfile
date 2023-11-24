pipeline {
    agent {
        dockerfile {
            label 'cross-compiler'
        }
    }

    stages {
        stage('Debug') {
            steps {
                sh 'mvn --version'
                sh 'make -C config-model all'
            }
        }
        stage('Maven install') {
            steps {
                sh 'mvn -DskipTests -X install'
            }
        }
        stage('Make') {
            steps {
                sh 'make all'
            }
        }
//         stage('Re-tag and push') {
//             when {
//               expression {
//                 currentBuild.result == null || currentBuild.result == 'SUCCESS'
//               }
//             }
//             steps {
//                 sh 'make publish'
//             }
//         }
    }
}
