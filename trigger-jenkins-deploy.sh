#!/usr/bin/env bash
set -e

wget -q --auth-no-challenge --user $JENKINS_USER --password $JENKINS_API_TOKEN --output-document crumb 'https://fc-jenkins.dsp-techops.broadinstitute.org/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,":",//crumb)'
cat crumb
echo "running curl now"
curl -X --POST https://fc-jenkins.dsp-techops.broadinstitute.org/job/gpalloc-deploy/buildWithParameters?token=$GPALLOC_DEPLOY_TOKEN --user $JENKINS_USER:$JENKINS_API_TOKEN --data-urlencode json='{"parameter": [{"name":"IMAGE", "value":"$CIRCLE_BRANCH"}]}' -H '$(cat crumb)'
