#!/bin/bash
set -e

echo "WARNING: You are about to force-push your local develop branch onto broadinstitute/gpalloc:master."
echo "If you really want to do this, type the word \"release\" and hit [ENTER]:"

read release

if [ "$release" = "release" ];
then
	git push origin develop:master --force
else
	echo "escape!"
fi

