#!/usr/bin/groovy


def dockerPull(image) {
    retryWithDelay(3, 120, {
        sh "docker pull ${image}"
    })
}

String getAWSDockerRepo() {
    def registryId = readFromInfraState("docker_registry_id")
    return "${registryId}.dkr.ecr.us-west-2.amazonaws.com"
}

def withAWSDocker(groovy.lang.Closure code) {
    docker.withRegistry("https://${getAWSDockerRepo()}", 'ecr:us-west-2:SW_FULL_AWS_CREDS') {
        code()
    }
}

def isKubernetesSupported(supportedSinceMajorVersion, currentMajorVersion) {
    return currentMajorVersion >= supportedSinceMajorVersion
}

def withDockerHubCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'dockerhub', passwordVariable: 'DOCKERHUB_PASSWORD', usernameVariable: 'DOCKERHUB_USERNAME')]) {
        code()
    }
}

Integer getDockerImageVersion() {
    def versionLine = readFile("gradle.properties").split("\n").find() { line -> line.startsWith('dockerImageVersion') }
    return versionLine.split("=")[1].toInteger()
}

def getSupportedSparkVersions() {
    def versionLine = readFile("gradle.properties").split("\n").find() { line -> line.startsWith('supportedSparkVersions') }
    sparkVersions = versionLine.split("=")[1].split(" ")

    return sparkVersions
}

def getTestingBaseImage() {
    def versionLine = readFile("gradle.properties").split("\n").find() { line -> line.startsWith('testingBaseImage') }
    return versionLine.split("=")[1]
}

def getSupportedPythonVersions(sparkVersion) {
    def versionLine = readFile("gradle-spark${sparkVersion}.properties")
        .split("\n")
        .find() { line -> line.startsWith('supportedPythonVersions') }
    return versionLine.split("=")[1].split(" ")
}

def withDocker(image, groovy.lang.Closure code, String dockerOptions = "", groovy.lang.Closure initCode = {}) {
    dockerPull(image)
    docker.image(image).inside(dockerOptions) {
        initCode()
        code()
    }
}

def withSparklingWaterDockerImage(code) {
    def repoUrl = getAWSDockerRepo()
    withAWSDocker {
        def image = "${repoUrl}/opsh2oai/sparkling_water_tests:" + getDockerImageVersion()
        def dockerOptions = "--init --privileged"
        groovy.lang.Closure initCode = {
            sh "activate_java_8"
        }
        withDocker(image, code, dockerOptions, initCode)
    }
}

def withTerraform(groovy.lang.Closure code, dockerOptions = "--entrypoint=''") {
    def terraformVersion = loadGradleProperties("gradle.properties")['terraformVersion']
    withDocker("hashicorp/terraform:$terraformVersion", code, dockerOptions)
}

def withPacker(groovy.lang.Closure code) {
    withDocker("hashicorp/packer:1.5.5", code, "--entrypoint=''")
}

def packerBuild() {
    sh """
        packer build -var 'aws_access_key=$AWS_ACCESS_KEY_ID' -var 'aws_secret_key=$AWS_SECRET_ACCESS_KEY' Jenkins-slave-template.json
        """
    readFile("manifest.json").split("\n")
            .find() { line -> line.startsWith("      \"artifact_id\":") }
            .split("\"")[3].split(":")[1].trim()
}
def terraformApply(extraVars = "") {
    sh """
        terraform init
        terraform apply -var aws_access_key=$AWS_ACCESS_KEY_ID -var aws_secret_key=$AWS_SECRET_ACCESS_KEY $extraVars -auto-approve
        """
}

def terraformDestroy(extraVars = "") {
    sh """
        terraform init
        terraform destroy -var aws_access_key=$AWS_ACCESS_KEY_ID -var aws_secret_key=$AWS_SECRET_ACCESS_KEY $extraVars -auto-approve
        """
}

def terraformImport(ami) {
    sh """
        terraform init
        terraform import -var 'aws_access_key=$AWS_ACCESS_KEY_ID' -var 'aws_secret_key=$AWS_SECRET_ACCESS_KEY' module.ami.aws_ami.jenkins-slave ${ami}
        """
}

def extractTerraformOutputs(List<String> varNames) {
    return varNames.collectEntries { [(it): terraformOutput(it)] }
}

def readFromInfraState(varName) {
    def line = readFile("ci/aws/terraform/infra.properties").split("\n").find() { line ->
        line.startsWith(varName)
    }
    return line.split("=")[1]
}

def terraformOutput(varName) {
    return sh(
            script: "terraform output $varName",
            returnStdout: true
    ).trim()
}

def withAWSCredentials(groovy.lang.Closure code) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'SW_FULL_AWS_CREDS', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        code()
    }
}

def withGitPushCredentials(groovy.lang.Closure code) {
    withCredentials([string(credentialsId: 'SW_GITHUB_TOKEN', variable: 'GITHUB_TOKEN')]) {
        sh """
            git config --global user.name "h2o-ops"
            git config --global user.email "h2o-ops@h2o.ai"
            """
        code()
    }
}

def withGitPullCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'h2o-ops-gh-2020', usernameVariable: 'GITHUB_PULL_USER', passwordVariable: 'GITHUB_PULL_PASS')]) {
        code()
    }
}

def withGitPrivateKey(groovy.lang.Closure code) {
    withCredentials([sshUserPrivateKey(credentialsId: 'h2oOpsGitPrivateKey', usernameVariable: 'GITHUB_SSH_USER', keyFileVariable: 'GITHUB_SSH_KEY')]) {
        code()
    }
}

def withAWSPrivateKey(groovy.lang.Closure code) {
    withCredentials([sshUserPrivateKey(credentialsId: 'SW_AWS_PRIVATE_KEY', keyFileVariable: 'AWS_SSH_KEY')]) {
        code()
    }
}

def withJenkinsCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'SW_JENKINS', usernameVariable: 'SW_JENKINS_USER', passwordVariable: 'SW_JENKINS_PASS')]) {
        code()
    }
}

def withDatabricksCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'SW_DATABRICKS', usernameVariable: 'DATABRICKS_HOST', passwordVariable: 'DATABRICKS_TOKEN')]) {
        code()
    }
}

def withDAICredentials(groovy.lang.Closure code) {
    withCredentials([string(credentialsId: 'DRIVERLESS_AI_LICENSE_KEY', variable: 'DRIVERLESS_AI_LICENSE_KEY')]) {
        code()
    }
}

def withSigningCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'SIGNING_KEY', usernameVariable: 'SIGN_KEY', passwordVariable: 'SIGN_PASSWORD'),
                     file(credentialsId: 'release-secret-key-ring-file', variable: 'RING_FILE_PATH')]) {
        code()
    }
}

def withNexusCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: "PUBLIC_NEXUS", usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
        code()
    }
}

def withPipyCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: "pypi-credentials", usernameVariable: 'PIPY_USERNAME', passwordVariable: 'PIPY_PASSWORD')]) {
        code()
    }
}

def withCondaCredentials(groovy.lang.Closure code) {
    withCredentials([usernamePassword(credentialsId: 'anaconda-credentials', usernameVariable: 'ANACONDA_USERNAME', passwordVariable: 'ANACONDA_PASSWORD')]) {
        code()
    }
}

def gitCommit(files, msg) {
    sh """
                git config --add remote.origin.fetch +refs/heads/${BRANCH_NAME}:refs/remotes/origin/${BRANCH_NAME}
                git fetch --no-tags
                git checkout ${BRANCH_NAME}
                git pull
                git add ${files.join(" ")}
                git config remote.origin.url "https://${GITHUB_TOKEN}@github.com/h2oai/sparkling-water.git"
                git commit -m "${msg}"
                git push --set-upstream origin ${BRANCH_NAME}
               """
}

def installDocker() {
    sh "sudo apt-get update"
    sh "sudo apt -y install docker.io"
    sh "sudo service docker start"
    sh "sudo chmod 666 /var/run/docker.sock"
}

def removeSparkImages(sparkVersion) {
    sh """
        docker rmi spark-r:${sparkVersion}
        docker rmi spark-py:${sparkVersion}
        docker rmi spark:${sparkVersion}
       """
}

def publishDockerImages(version, code) {
    withDockerHubCredentials {
        docker.withRegistry('', 'dockerhub') {
            dir("./dist/build/zip/sparkling-water-${version}") {
                code()
            }
        }
    }
}

def loadGradleProperties(file) {
    def properties = [:]
    readFile(file).split("\n").each { line ->
        if (!line.startsWith("#")) {
            def splits = line.split("=")
            properties[splits[0]] = splits[1]
        }
    }
    return properties
}

return this
