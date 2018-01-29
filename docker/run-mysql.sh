#!/usr/bin/env bash

# The CloudSQL console simply states "MySQL 5.6" so we may not match the minor version number
MYSQL_VERSION=5.6
start() {

    echo "attempting to remove old $CONTAINER container..."
    docker rm -f $CONTAINER || echo "docker rm failed. nothing to rm."

    # start up mysql
    echo "starting up mysql container..."
    docker run --name $CONTAINER -e MYSQL_ROOT_PASSWORD=gpalloc-test -e MYSQL_USER=gpalloc-test -e MYSQL_PASSWORD=gpalloc-test -e MYSQL_DATABASE=gpalloctestdb -d -p 3311:3306 mysql/mysql-server:$MYSQL_VERSION

    # validate mysql
    echo "running mysql validation..."
    docker run --rm --link $CONTAINER:mysql -v $PWD/docker/validate-sql.sh:/working/validate-sql.sh mysql:$MYSQL_VERSION /working/validate-sql.sh gpalloc
    if [ 0 -eq $? ]; then
        echo "mysql validation succeeded."
    else
        echo "mysql validation failed."
        exit 1
    fi

}

stop() {
    echo "Stopping docker $CONTAINER container..."
    docker stop $CONTAINER || echo "mysql stop failed. container already stopped."
    docker rm -v $CONTAINER || echo "mysql rm -v failed.  container already destroyed."
}

CONTAINER=mysql

if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start <service>"
    exit 1
fi

COMMAND=$1

if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start"
    exit 1
fi

if [ $COMMAND = "start" ]; then
    start
elif [ $COMMAND = "stop" ]; then
    stop
else
    exit 1
fi