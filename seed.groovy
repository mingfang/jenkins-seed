import org.yaml.snakeyaml.Yaml
def yaml = new Yaml()
Map servicesYaml = yaml.load(readFileFromWorkspace('services.yml'))

servicesYaml.services.each{
  def service = it
  def pod = service['pod']
  List containers = (pod != null) ? pod['containers'] : []
  containers.each{
    def container = it
    def imageParts = parseImage(container.image)
    def shaTag = imageParts.registry + '/' + imageParts.name + ':${GIT_COMMIT}'
    def latestTag = imageParts.registry + '/' + imageParts.name + ':latest'

    if(container.scm && imageParts.registry){
      job(service.name){
        description('service: ' + service.name + '\nimage: ' + container.image)
        parameters {
          booleanParam('no_cache', false, 'Check to disable Docker cache')
        }
        scm {
       	  git {
            remote {
              url(container.scm)
            }
            branch('master')
          }
        }
        triggers {
          scm('H/15 * * * *')
          cron('H H * * *')
        }
        concurrentBuild(false)
        logRotator(-1, 10)
        wrappers{
          buildName('#${BUILD_NUMBER}-${GIT_COMMIT}')
        }
        publishers{
          publishHtml {
//            report('html') {
//                reportName('Clair Report')
//                reportFiles('analysis-' + imageParts.name + '-${GIT_COMMIT}.html')
//            }
            retryBuild {
              retryLimit(3)
              progressiveDelay(60, 600)
            }
          }

          chucknorris()
          slackNotifier {
            room('@mingfang')
            notifyAborted(true)
            notifyFailure(true)
            notifyNotBuilt(true)
            notifyUnstable(true)
            notifyBackToNormal(true)
            notifySuccess(true)
            notifyRepeatedFailure(false)
            startNotification(true)
            includeTestSummary(false)
            includeCustomMessage(false)
            customMessage(null)
            sendAs(null)
            commitInfoChoice('NONE')
            teamDomain(null)
            authToken(null)
            authTokenCredentialId(null)
          }
        }
        steps{
          shell('docker build $([ "$no_cache" = true ] && echo "--no-cache") --pull --build-arg http_proxy="$http_proxy" --build-arg https_proxy="$https_proxy"' 
                + ' -t ' + shaTag + ' .')
          shell('docker push ' + shaTag)
          shell('docker tag ' + shaTag + ' ' + latestTag)
          shell('docker push ' + latestTag)
//          shell('hyperclair push --config=/etc/hyperclair.yml --log-level=debug ' + shaTag + ' && hyperclair analyse --config=/etc/hyperclair.yml --log-level=debug ' + shaTag + ' && hyperclair report --config=/etc/hyperclair.yml --log-level=debug ' + shaTag)
        }
        
        properties{
          promotions{
            promotion{
              name('1-Deploy INT')
              icon('star-gold')
              conditions{
                selfPromotion(false)
              }
              actions{
                shell('echo Deploy INT ${PROMOTED_GIT_COMMIT}')
                shell('kubectl --server http://${KMASTER}:8080 set image deployment/' + service.name + '  ' + container.name + '=' + imageParts.registry + '/' + imageParts.name + ':${PROMOTED_GIT_COMMIT}')
              }
            }
            promotion{
              name('2-Deploy QA')
              icon('star-green')
              conditions{
                manual('')
              }
              actions{
                downstreamParameterized{
                  trigger('Deploy INT'){
                    block {
    	                buildStepFailure('FAILURE')
	                    failure('FAILURE')
                    	unstable('UNSTABLE')
                	}
                    parameters {
                      predefinedProp('MSG', 'test')
                      predefinedProp('CMD', 'kubectl --server http://${KMASTER}:8080 set image deployment/' + service.name + '  ' + container.name + '=' + imageParts.registry + '/' + imageParts.name + ':${PROMOTED_GIT_COMMIT}')
                	}
                  }
                }
              }
            }
            promotion{
              name('3-Deploy PERF')
              icon('star-purple')
              conditions{
                manual('')
              }
              actions{
                shell('echo Deploy PERF')
              }
            }
           
          }
        }
      }
    }
  }
}

job('Deploy INT'){
  parameters{
    stringParam("MSG", "whatever", "whatever")
    stringParam("CMD", "", "Command to run")
  }
  steps{
    shell('echo "Deploy INT"')
    shell("\${CMD}")
  }
  publishers{
    chucknorris()
    slackNotifier {
      room('@mingfang')
      startNotification(true)
      notifySuccess(true)
      notifyAborted(true)
      notifyFailure(true)
      notifyNotBuilt(false)
      notifyUnstable(false)
      notifyBackToNormal(false)
      notifyRepeatedFailure(false)
      includeTestSummary(false)
      includeCustomMessage(true)
      customMessage("\${MSG}")
      sendAs(null)
      commitInfoChoice('NONE')
      teamDomain(null)
      authToken(null)
      authTokenCredentialId(null)
    }
  }
  
}

job("Kubernetes-Playbook"){
  multiscm {
    git {
      remote {
        url('git@github.com:mingfang/kubernetes-playbook.git')
      }
      extensions {
        relativeTargetDirectory('kubernetes-playbook')
      }
    }
    git {
      remote {
        url('git@github.com:mingfang/jenkins-seed.git')
      }
      extensions {
        relativeTargetDirectory('jenkins-seed')
      }
    }
  }
  triggers {
    scm('H/15 * * * *')
    githubPush()
  }
  concurrentBuild(false)
  logRotator(-1, 10)
  label('master')
  steps{
    shell('ansible-playbook kubernetes-playbook/kubernetes-playbook.yml -i jenkins-seed/services.ini -e "@jenkins-seed/services.yml"')
  }
}

listView('Playbooks') {
  description('Ansible Playbooks')
  jobs {
    regex(/.*-Playbook/)
  }
  columns {
    status()
    weather()
    name()
    lastSuccess()
    lastFailure()
    lastDuration()
    buildButton()
  }
}

Map parseImage(String image){
  def allParts = [:]
  def nameTagParts = ""
  def splitSlash = image.split('/')
  if(splitSlash.length == 2){
    allParts['registry'] = splitSlash[0]
    nameTagParts = splitSlash[1]
  }else{
    nameTagParts = image
  }
  def splitColon = nameTagParts.split(':')
  if(splitColon.length == 2){
    allParts['name'] = splitColon[0]
    allParts['tag'] = splitColon[1]
  }else{
    allParts['name'] = nameTagParts
  }
  
  return allParts
}

job("elasticsearch cleanup"){
  triggers {
    cron('@midnight')
  }
  concurrentBuild(false)
  logRotator(-1, 10)
  steps{
    shell("curl -s elasticsearch-master:9200/_cat/indices|awk \'{print \$3}\'|grep logstash-*|sort -r|sed 1,5d|xargs -I {} curl -s elasticsearch-master:9200/{} -v -X DELETE")
    shell("curl -s elasticsearch-master:9200/_cat/indices|awk \'{print \$3}\'|grep packetbeat-*|sort -r|sed 1,5d|xargs -I {} curl -s elasticsearch-master:9200/{} -v -X DELETE")
  }
}

