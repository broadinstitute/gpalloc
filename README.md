# allo, gator

Makes creating billing projects snappy

## Using GPAlloc

For instructions on how to use GPAlloc to provide you with Google projects, see [here](USAGE.md).

If you need help debugging GPAlloc-related errors, see [here](HELP.md).

## Managing GPAlloc

There are three GPAlloc instances, all of which live in the `broad-dsp-techops` Google project.

### Instances

#### For use in tests

The two "production" instances of GPAlloc are:

**https://gpalloc-dev.dsp-techops.broadinstitute.org/**

* Creates projects in the @test.firecloud.org domain
* Used by tests run against FiaBs created in the broad-dsde-dev project (i.e. developer-triggered test runs)

**https://gpalloc-qa.dsp-techops.broadinstitute.org/**

* Creates projects in the @quality.firecloud.org domain
* Used by tests run against FiaBs created in the broad-dsde-qa project (i.e. auto-triggered test runs)

#### For GPAlloc developers

https://gpalloc-beta.dsp-techops.broadinstitute.org/ is the "developer" instance. At any given point in time it probably has recent-ish code on it, but you should ssh to the host and run `sudo docker ps` to find out.

### Deployment

[CircleCI](https://circleci.com/gh/broadinstitute/gpalloc) builds Docker images for the `develop` and `master` branches of this repository on commits to those branches.

Deployment is handled through the [gpalloc-deploy](https://fc-jenkins.dsp-techops.broadinstitute.org/job/gpalloc-deploy/) Jenkins job.

To deploy the `develop` Docker image to the `-beta` instance, select `image=develop`.

To deploy the `master` Docker image to the `-dev` and `-qa` instances, select `image=master`.

The `-beta` instance can be used to test WIP code. Please synchronize with the #gpalloc channel on Broad's Slack before doing this.

Note that the git branch name is used in the created project names, so  

a) don't make it too long  
b) **don't put an underscore in it**.  

Otherwise Google won't let you create the project (name too long or contains invalid characters).

To deploy something custom to `-beta`, do the following:

1. Manually build and push your branch of gpalloc to DockerHub:  
```
local $ ./docker/build.sh jar
local $ ./docker/build.sh -d build
local $ ./docker/build.sh -d push
```  
**Jenkins automated deployment**

2. Determine the IP of the `-beta` host with `nslookup http://gpalloc-beta-priv.dsp-techops.broadinstitute.org`
3. Go to the [gpalloc-instance-deploy](https://fc-jenkins.dsp-techops.broadinstitute.org/view/Deploy/job/gpalloc-instance-deploy/) Jenkins job.
4. In `Build with Parameters`:
   - `PRIV_HOST` : the IP from step 2
   - `IMAGE` : the name of the docker image from step 1 (default same as branch name)
   - `ENVIRONMENT` : dev
3. Edit `/app/docker-compose.yaml` to point to your new Docker image.
4. Click `Build`

**Manual Deployment**
1. SSH into the machine.
2. Edit `/app/docker-compose.yaml` to point to your new Docker image.
3. Restart the Docker:
```
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml stop
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml rm -f
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml pull
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml up -d
```

## Building and Running GPAlloc
Clone and go into the repo:
```
$ git clone https://github.com/broadinstitute/gpalloc.git
$ cd gpalloc
```
Spin up MySQL locally (ensure Docker is running):
```
$ ./docker/run-mysql.sh start gpalloc
```
Build GPAlloc and run the tests:
```
export SBT_OPTS="-Xmx2G -Xms1G -Dmysql.host=localhost -Dmysql.port=3311"
sbt clean compile test
```
Once you're done, tear down MySQL:
```
./docker/run-mysql.sh stop gpalloc
```
