# allo, gator

Makes creating billing projects snappy!

For instructions on how to use GPAlloc to provide you with Google projects, see [here](USAGE.md).

If you need help debugging GPAlloc-related errors, see [here](HELP.md).

# Developing GPAlloc

## Instances

There are three GPAlloc instances, all of which live in the `broad-dsp-techops` Google project.

### Instances for use in tests

The two "production" instances of GPAlloc are:

**https://gpalloc-dev.dsp-techops.broadinstitute.org/**

* Creates projects in the @test.firecloud.org domain
* Used by tests run against FiaBs created in the broad-dsde-dev project (i.e. developer-triggered test runs)

**https://gpalloc-qa.dsp-techops.broadinstitute.org/**

* Creates projects in the @quality.firecloud.org domain
* Used by tests run against FiaBs created in the broad-dsde-qa project (i.e. auto-triggered test runs)

Remember: despite a name that might indicate otherwise, **gpalloc-dev is a real instance used by our test environment. It is not for unreleased code.**

### Instances for GPAlloc developers

https://gpalloc-beta.dsp-techops.broadinstitute.org/ is the "developer" instance. At any given point in time it probably has recent-ish code on it, but you should ssh to the host and run `sudo docker ps` to find out.

## Development process

The conventions for developing on GPAlloc are a little different to what you're used to. The process goes as follows (the complicated steps will be outlined below, hold your horses):

1. Branch off `develop` and make your changes.
2. (Discretionary) Test your changes by [manually building the docker and using the gpalloc-instance-deploy Jenkins job to deploy it to gpalloc-beta](#development-cycle). Repeat until working.
3. PR to `develop` and review.
4. Wait for CircleCI to finish building off `develop`.
5. To release to `master`, run `./scripts/release_master.sh`. This will force-push `develop` on to `master`. Yes, you do really want to do this!
6. [Wait for CircleCI to finish building off `master`](#watching-circleci-for-auto-builds-of-develop-and-master).
7. [While you're waiting, make a new release in GitHub](#making-a-new-release-in-github)
8. Run the gpalloc-deploy Jenkins job to deploy to the "production" instances. [Instructions to deploy to production are here](#deploying-the-master-branch-to-gpalloc-dev-and-gpalloc-qa)

### Getting started

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

### Development cycle

Note that you may need to [start/resume the dsp-gpalloc-beta VM in the GCP Console](https://console.cloud.google.com/compute/instances?project=broad-dsp-techops). Auth as your @firecloud.org account.

Note that the git branch name is used in the created project names, so  

1. don't make it too long -- 9 characters maximum 
2. **don't put an underscore in it**.  

Otherwise Google won't let you create the project (name too long or contains invalid characters).

To deploy to gpalloc-beta, first manually build and push your branch of gpalloc to DockerHub:
  
```
local $ ./docker/build.sh jar
local $ ./docker/build.sh -d build
local $ ./docker/build.sh -d push
```

Then synchronize with the #gpalloc channel on Slack, just to let people know you're stealing gpalloc-beta.

Finally, run [gpalloc-instance-deploy](https://fc-jenkins.dsp-techops.broadinstitute.org/job/gpalloc-instance-deploy/) to deploy to gpalloc-beta. At the time of writing the value for `PRIV_HOST` you want is `10.255.55.42`, but it should be in the text of the Jenkins job. Use your Git branch name as the value for `IMAGE` name. Leave the `ENVIRONMENT` as `dev` (unless you really want the config for the `@quality.firecloud.org` domain).

You can then test your code on gpalloc-beta and repeat this cycle as needed.

### Watching CircleCI for auto-builds of `develop` and `master`

[CircleCI](https://circleci.com/gh/broadinstitute/gpalloc) builds Docker images for the `develop` and `master` branches of this repository on commits to those branches. Click the link to look at it.

### Making a new release in GitHub

Go to the [Releases](https://github.com/broadinstitute/gpalloc/releases) page in GitHub. Hit "Draft a new release". It should look like this:

![image](https://user-images.githubusercontent.com/775136/47816312-2e00c480-dd29-11e8-9e1f-e5d8c9cd007b.png)

Note that:
* The version is incremented
* The target is _the commit hash at the tip of master_, **not** the master branch itself. This is important!

This doesn't do anything per se, but it serves as a record of what got released and when.

### Deploying the `master` branch to gpalloc-dev and gpalloc-qa

Use the [gpalloc-deploy](https://fc-jenkins.dsp-techops.broadinstitute.org/job/gpalloc-deploy/) Jenkins job for this. This time, select `image=master`. This will deploy to _both_ gpalloc-dev and gpalloc-qa.

## Miscellaneous things

### Re-deploying gpalloc
Follow the [instructions to deploy to production are here](#deploying-the-master-branch-to-gpalloc-dev-and-gpalloc-qa)

### Certificate issues
If re-deploying causes cert issues, we may need to [update the path to the certs, like we did in this PR](https://github.com/broadinstitute/gpalloc/pull/107).

### Connecting to the gpalloc VM
Make sure you're on the Broad Internal wifi or on the **non-split** VPN. Run the following from the command line:

For gpalloc-dev: `gcloud beta compute ssh --zone "us-central1-a" "dsp-gpalloc-dev101"  --project "broad-dsp-techops" --account=xxxxxxxxxxxxxxxx@firecloud.org`

For gpalloc-qa: `gcloud beta compute ssh --zone "us-central1-a" "dsp-gpalloc-qa101"  --project "broad-dsp-techops" --account=xxxxxxxxxxxxxxxxx@firecloud.org`

#### Troubleshooting

##### ssh: connect to host ... port 22: Operation timed out ...
Connect to the Broad Internal wifi or on the **non-split** VPN

##### Could not fetch resource | The resource ... was not found
The VMs may have been replaced. [Look for the dsp-gpalloc VMs in the GCP Console](https://console.cloud.google.com/compute/instances?project=broad-dsp-techops). Auth as your @firecloud.org account. Update this doc as necessary.

##### Connection failed | We are unable to connect to the VM on port 22
Don't try to connect to the VPN using GCP. It will only end in tears.

### Deploying the `develop` branch to gpalloc-beta

We don't really have a "dev" environment of GPAlloc; gpalloc-beta is "scratch space for devs" and gpalloc-dev and gpalloc-qa are "production" instances for their respective Firecloud test domains. However, if you ever want to deploy whatever's on `develop` to gpalloc-beta, you can do that with the [gpalloc-deploy](https://fc-jenkins.dsp-techops.broadinstitute.org/job/gpalloc-deploy/) Jenkins job.

To deploy the `develop` Docker image to the `-beta` instance, select `image=develop`.

### Manually deploying to gpalloc-beta

This is deeply shenanigans and you shouldn't need do it, but it can be quicker if you're making rapid changes. (Caveat: you're unlikely to have SSH access to the machine, and Bernick probably won't give it to you.)

1. SSH into the machine.
2. Edit `/app/docker-compose.yaml` to point to your new Docker image.
3. Restart the Docker:
```
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml stop
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml rm -f
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml pull
gpalloc-beta $ sudo docker-compose -p gpalloc -f /app/docker-compose.yml up -d
```

### Communications

- GPalloc is used by various services in `dsp-workbench`. If you're re-creating projects pool, please send a heads-up for interruptions in `#dsp-workbench`.
