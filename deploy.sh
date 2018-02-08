#!/usr/bin/env bash

if [ -z "${SSHCMD}" ]; then
    echo "FATAL ERROR: SSHCMD undefined."
    exit 1
fi

if [ -z "${SSH_USER}" ]; then
    echo "FATAL ERROR: SSH_USER undefined."
    exit 2
fi

if [ -z "${SSH_HOST}" ]; then
    echo "FATAL ERROR: SSH_HOST undefined."
    exit 3
fi

if [ -z "${VAULT_TOKEN}" ]; then
    echo "FATAL ERROR: VAULT_TOKEN undefined."
    exit 4
fi

PROJECT=gpalloc
COMPOSE_FILE=docker-compose.yml

# Copy over configs & render certs from vault
# TODO: render configs and copy them onto host
docker run -e VAULT_TOKEN=$VAULT_TOKEN broadinstitute/dsde-toolbox vault read secret/dsde/dsp-techops/common/server.crt
docker run -e VAULT_TOKEN=$VAULT_TOKEN broadinstitute/dsde-toolbox vault read secret/dsde/dsp-techops/common/server.key
docker run -e VAULT_TOKEN=$VAULT_TOKEN broadinstitute/dsde-toolbox vault read secret/dsde/dsp-techops/common/ca-bundle.crt
# TODO: pull billing acct from vault


# Start new application container with the current version
$SSHCMD $SSH_USER@$SSH_HOST "echo 'on host'"
#"docker-compose -p $PROJECT -f $COMPOSE_FILE stop"
#$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE rm -f"
#$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE pull"
#$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE up -d"
#
## Remove any dangling images that might be hanging around
#$SSHCMD $SSH_USER@$SSH_HOST "docker images -aq --no-trunc --filter dangling=true | xargs docker rmi || /bin/true"
