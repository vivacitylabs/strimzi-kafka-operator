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
                sh 'env'
                sh 'pwd'
                sh 'ls -alphs1 || exit 0'
                sh 'ls -al /root/.m2 || exit 0'
                sh 'ls -al $HOME/.m2 || exit 0'
                sh 'cat /usr/share/java/maven-3/bin/m2.conf || exit 0'
            }
        }
        stage('Maven install') {
            steps {
                sh 'export MAVEN_HOME=/usr/share/maven ; mvn -DskipTests -X install'
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
