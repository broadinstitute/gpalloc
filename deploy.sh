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

if [ -z "${ENV}" ]; then
    echo "FATAL ERROR: ENV undefined."
    exit 4
fi


PROJECT=gpalloc
COMPOSE_FILE=docker-compose.yml
VAULT_TOKEN=$(cat /etc/vault-token-dsde)
OUTPUT_DIR=app
INPUT_DIR=configs
BRANCH=${BRANCH:-develop}

docker run --rm -v $PWD:/working -w /working \
    -e APP_NAME=$PROJECT \
    -e VAULT_TOKEN=$VAULT_TOKEN \
    -e INPUT_DIR=/working/configs \
    -e OUTPUT_DIR=/working/app \
    -e IMAGE=$BRANCH \
    -e ENV=$ENV \
    broadinstitute/dsde-toolbox:dev ruby configure.rb -y

scp -r $SSHOPTS app/* $SSH_USER@$SSH_HOST:/app


# Start new application container with the current version
$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE stop"
$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE rm -f"
$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE pull"
$SSHCMD $SSH_USER@$SSH_HOST "docker-compose -p $PROJECT -f $COMPOSE_FILE up -d"

# Remove any dangling images that might be hanging around
$SSHCMD $SSH_USER@$SSH_HOST "docker images -aq --no-trunc --filter dangling=true | xargs docker rmi || /bin/true"
